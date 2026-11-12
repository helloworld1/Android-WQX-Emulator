#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif
#include "nc1020.h"
#include "cpu6502.h"
#include "nc1020_states.h"
#include "nc1020_io.h"
#include "nc1020_dsp.h"
#include "nc1020_sound.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <time.h>

// cpu cycles per second (cpu freq).
const uint64_t CYCLES_SECOND = 5120000;
const uint64_t TIMER0_FREQ = 2;
const uint64_t TIMER1_FREQ = 0x100;
// cpu cycles per timer0 period (1/2 s).
const uint64_t CYCLES_TIMER0 = CYCLES_SECOND / TIMER0_FREQ;
// cpu cycles per timer1 period (1/256 s).
const uint64_t CYCLES_TIMER1 = CYCLES_SECOND / TIMER1_FREQ;
// speed up
const uint64_t CYCLES_TIMER1_SPEED_UP = CYCLES_SECOND / TIMER1_FREQ / 20;
// cpu cycles per ms (1/1000 s).
const uint64_t CYCLES_MS = CYCLES_SECOND / 1000;

static const uint64_t ROM_SIZE = 0x8000 * 0x300;
static const uint64_t NOR_SIZE = 0x8000 * 0x20;

static const uint16_t IO_LIMIT = 0x40;

static const uint16_t NMI_VEC = 0xFFFA;
static const uint16_t RESET_VEC = 0xFFFC;

static const uint64_t VERSION = 0x06;

static const int MAX_FILE_NAME_LENGTH = 255;

static char _rom_file_path[MAX_FILE_NAME_LENGTH];
static char _nor_file_path[MAX_FILE_NAME_LENGTH];
static char _state_file_path[MAX_FILE_NAME_LENGTH];

static uint8_t _rom_buff[ROM_SIZE];
static uint8_t _nor_buff[NOR_SIZE];

static uint8_t *_nor_banks[0x20];

static uint8_t *_memmap[8];
static nc1020_states_t _nc1020_states;

static uint8_t *_ram_buff;
static uint8_t *_ram_page0;
static uint8_t *_ram_page2;
static uint8_t *_ram_page3;

static uint8_t *_clock_buff;

static uint8_t *_jg_wav_buff;

static uint8_t *_fp_buff;

static uint8_t *_keypad_matrix;

static bool _tm0_running = false;
static uint64_t _tm0_slice_deadline = 0;
static uint64_t _current_slice_cycles = 0;
static bool _pending_cold_boot_sync = false;
static bool _has_tz_offset = false;
static int32_t _tz_offset_sec = 0;

static void sync_time(void);

static uint64_t tm0_cycles_for_counter(uint8_t counter) {
    uint8_t io0c = _ram_buff[0x0C];
    uint8_t tmodesl = io0c >> 6u;
    uint8_t shift = (tmodesl == 1u) ? ((io0c >> 4u) & 3u) : ((io0c >> 3u) & 7u);
    uint64_t ticks = (uint64_t) (256u - counter) << shift;
    uint64_t c = (ticks * 504u) / 193u;
    return c < 16u ? 16u : c;
}

void nc1020_tm0_start(uint8_t counter) {
    _tm0_running = true;
    _tm0_slice_deadline = _current_slice_cycles + tm0_cycles_for_counter(counter);
}

void nc1020_tm0_stop(void) {
    _tm0_running = false;
}

void nc1020_tm0_set_counter(uint8_t counter) {
    if (_tm0_running) {
        _tm0_slice_deadline = _current_slice_cycles + tm0_cycles_for_counter(counter);
    }
}

static void adjust_time(){
    if (++ _clock_buff[0] >= 60) {
        _clock_buff[0] = 0;
        if (++ _clock_buff[1] >= 60) {
            _clock_buff[1] = 0;
            if (++ _clock_buff[2] >= 24) {
                _clock_buff[2] &= 0xC0u;
                ++ _clock_buff[3];
            }
        }
    }
}

static bool is_count_down(){
    if (!(_clock_buff[10] & 0x02u) ||
        !(_nc1020_states.clock_flags & 0x02u)) {
        return false;
    }
    return (
        ((_clock_buff[7] & 0x80u) && !(((_clock_buff[7] ^ _clock_buff[2])) & 0x1Fu)) ||
        ((_clock_buff[6] & 0x80u) && !(((_clock_buff[6] ^ _clock_buff[1])) & 0x3Fu)) ||
        ((_clock_buff[5] & 0x80u) && !(((_clock_buff[5] ^ _clock_buff[0])) & 0x3Fu))
        );
}

/**
 * ProcessBinary
 * encrypt or decrypt wqx's binary file. just flip every bank.
 */
static void process_binary(uint8_t *dest, uint8_t *src, uint64_t size){
	uint64_t offset = 0;
    while (offset < size) {
        memcpy(dest + offset + 0x4000, src + offset, 0x4000);
        memcpy(dest + offset, src + offset + 0x4000, 0x4000);
        offset += 0x8000;
    }
}

static void load_rom(){
	uint8_t* temp_buff = (uint8_t*)malloc(ROM_SIZE);
	FILE* file = fopen(_rom_file_path, "rbe");
	fread(temp_buff, 1, ROM_SIZE, file);
    process_binary(_rom_buff, temp_buff, ROM_SIZE);
	free(temp_buff);
	fclose(file);
}

static void load_nor(){
	uint8_t* temp_buff = (uint8_t*)malloc(NOR_SIZE);
	FILE* file = fopen(_nor_file_path, "rbe");
	fread(temp_buff, 1, NOR_SIZE, file);
    process_binary(_nor_buff, temp_buff, NOR_SIZE);
	free(temp_buff);
	fclose(file);
}

static void save_nor(){
	uint8_t* temp_buff = (uint8_t*)malloc(NOR_SIZE);
	FILE* file = fopen(_nor_file_path, "wbe");
    process_binary(temp_buff, _nor_buff, NOR_SIZE);
	fwrite(temp_buff, 1, NOR_SIZE, file);
	fflush(file);
	free(temp_buff);
	fclose(file);
}

static uint8_t peek_byte(uint16_t addr) {
	return _memmap[addr / 0x2000][addr % 0x2000];
}

static uint16_t peek_word(uint16_t addr) {
	return peek_byte(addr) | (peek_byte((uint16_t) (addr + 1u)) << 8u);
}
static uint8_t load(uint16_t addr) {
	if (addr < IO_LIMIT) {
		return read_io((uint8_t) addr);
	}
	if (((_nc1020_states.fp_step == 4 && _nc1020_states.fp_type == 2) ||
		(_nc1020_states.fp_step == 6 && _nc1020_states.fp_type == 3)) &&
		(addr >= 0x4000 && addr < 0xC000)) {
		_nc1020_states.fp_step = 0;
		return 0x88;
	}
	if (addr == 0x45F && _nc1020_states.pending_wake_up) {
		_nc1020_states.pending_wake_up = false;
		_memmap[0][0x45F] = _nc1020_states.wake_up_flags;
	}
	return peek_byte(addr);
}

static void store(uint16_t addr, uint8_t value) {
	if (addr < IO_LIMIT) {
		write_io((uint8_t) addr, value);
		return;
	}
	if (addr < 0x4000) {
        _memmap[addr / 0x2000][addr % 0x2000] = value;
		if (_pending_cold_boot_sync && addr == 0x074C && value == 0x84u) {
			_pending_cold_boot_sync = false;
			sync_time();
		}
		return;
	}
	uint8_t* page = _memmap[addr >> 13u];
	if (page == _ram_page2 || page == _ram_page3) {
		page[addr & 0x1FFFu] = value;
		return;
	}
	if (addr >= 0xE000) {
		return;
	}

    // write to nor_flash address space.
    // there must select a nor_bank.

    uint8_t bank_idx = read_io(0x00);
    if (bank_idx >= 0x20) {
        return;
    }

    uint8_t* bank = _nor_banks[bank_idx];

    if (_nc1020_states.fp_step == 0) {
        if (addr == 0x5555 && value == 0xAA) {
            _nc1020_states.fp_step = 1;
        }
        return;
    }
    if (_nc1020_states.fp_step == 1) {
        if (addr == 0xAAAA && value == 0x55) {
        	_nc1020_states.fp_step = 2;
            return;
        }
    } else if (_nc1020_states.fp_step == 2) {
        if (addr == 0x5555) {
            switch (value) {
                case 0x90: _nc1020_states.fp_type = 1; break;
                case 0xA0: _nc1020_states.fp_type = 2; break;
                case 0x80: _nc1020_states.fp_type = 3; break;
                case 0xA8: _nc1020_states.fp_type = 4; break;
                case 0x88: _nc1020_states.fp_type = 5; break;
                case 0x78: _nc1020_states.fp_type = 6; break;
                default:break;
            }
            if (_nc1020_states.fp_type) {
                if (_nc1020_states.fp_type == 1) {
                    _nc1020_states.fp_bank_idx = bank_idx;
                    _nc1020_states.fp_bak1 = bank[0x4000];
                    _nc1020_states.fp_bak1 = bank[0x4001];
                }
                _nc1020_states.fp_step = 3;
                return;
            }
        }
    } else if (_nc1020_states.fp_step == 3) {
        if (_nc1020_states.fp_type == 1) {
            if (value == 0xF0) {
                bank[0x4000] = _nc1020_states.fp_bak1;
                bank[0x4001] = _nc1020_states.fp_bak2;
                _nc1020_states.fp_step = 0;
                return;
            }
        } else if (_nc1020_states.fp_type == 2) {
            bank[addr - 0x4000] &= value;
            _nc1020_states.fp_step = 4;
            return;
        } else if (_nc1020_states.fp_type == 4) {
            _fp_buff[addr & 0xFFu] &= value;
            _nc1020_states.fp_step = 4;
            return;
        } else if (_nc1020_states.fp_type == 3 || _nc1020_states.fp_type == 5) {
            if (addr == 0x5555 && value == 0xAA) {
                _nc1020_states.fp_step = 4;
                return;
            }
        }
    } else if (_nc1020_states.fp_step == 4) {
        if (_nc1020_states.fp_type == 3 || _nc1020_states.fp_type == 5) {
            if (addr == 0xAAAA && value == 0x55) {
                _nc1020_states.fp_step = 5;
                return;
            }
        }
    } else if (_nc1020_states.fp_step == 5) {
        if (addr == 0x5555 && value == 0x10) {
        	for (uint64_t i=0; i<0x20; i++) {
                memset(_nor_banks[i], 0xFF, 0x8000);
            }
            if (_nc1020_states.fp_type == 5) {
                memset(_fp_buff, 0xFF, 0x100);
            }
            _nc1020_states.fp_step = 6;
            return;
        }
        if (_nc1020_states.fp_type == 3) {
            if (value == 0x30) {
                memset(bank + (addr - (addr % 0x800) - 0x4000), 0xFF, 0x800);
                _nc1020_states.fp_step = 6;
                return;
            }
        } else if (_nc1020_states.fp_type == 5) {
            if (value == 0x48) {
                memset(_fp_buff, 0xFF, 0x100);
                _nc1020_states.fp_step = 6;
                return;
            }
        }
    }
    if (addr == 0x8000 && value == 0xF0) {
        _nc1020_states.fp_step = 0;
        return;
    }
    printf("error occurs when operate in flash!");
}

static void apply_nc1020_datetime(int year, int month, int day, int wday, int hour, int minute, int second) {
    if (year < 1932) {
        year = 1932;
    } else if (year > 2031) {
        year = 2031;
    }
    if (month < 1) {
        month = 1;
    } else if (month > 12) {
        month = 12;
    }
    if (day < 1) {
        day = 1;
    } else if (day > 31) {
        day = 31;
    }
    wday = ((wday % 7) + 7) % 7;
    if (hour < 0) {
        hour = 0;
    } else if (hour > 23) {
        hour = 23;
    }
    if (minute < 0) {
        minute = 0;
    } else if (minute > 59) {
        minute = 59;
    }
    if (second < 0) {
        second = 0;
    } else if (second > 59) {
        second = 59;
    }

    uint8_t enc_year = (uint8_t) (year - 1881);
    uint8_t enc_mon = (uint8_t) (month - 1);
    uint8_t enc_day = (uint8_t) (day - 1);
    uint8_t enc_wday = (uint8_t) wday;

    uint8_t old_year = _ram_buff[0x0472];
    uint8_t old_mon = _ram_buff[0x0473];
    uint8_t old_day = _ram_buff[0x0474];
    bool date_changed = (old_year != enc_year ||
                         old_mon != enc_mon ||
                         old_day != enc_day ||
                         _ram_buff[0x0475] != enc_wday);

    // $046F..$0475 (1135..1141): hour, min, sec*2, year-1881, 0-based month, 0-based day, weekday (0=Sun..6=Sat)
    _ram_buff[0x046F] = (uint8_t) hour;
    _ram_buff[0x0470] = (uint8_t) minute;
    _ram_buff[0x0471] = (uint8_t) (second * 2);
    _ram_buff[0x0472] = enc_year;
    _ram_buff[0x0473] = enc_mon;
    _ram_buff[0x0474] = enc_day;
    _ram_buff[0x0475] = enc_wday;

    // Hardware RTC registers ($3E/$3F): preserve bits 6..7 of reg 2, zero sleep elapsed-day counter reg 3
    _clock_buff[0] = (uint8_t) second;
    _clock_buff[1] = (uint8_t) minute;
    _clock_buff[2] = (uint8_t) ((_clock_buff[2] & 0xC0u) | ((uint8_t) hour & 0x1Fu));
    _clock_buff[3] = 0;

    if (!_nc1020_states.slept && _ram_buff[0x0460] == 0xA5u) {
        _ram_buff[0x047A] |= 0x80u;
        _ram_buff[0x0462] |= 0x80u;
        if (date_changed) {
            _ram_buff[0x074C] |= 0x04u;
            // If sitting idle on the main Clock/Calendar screen (vol 0, bank 0x83), trigger its
            // silent $4580 calendar+lunar+clock refresh handler so the displayed date updates immediately.
            if ((_ram_buff[0x0D] & 0x03u) == 0u &&
                _ram_buff[0x00] == 0x83u &&
                (_ram_buff[0x0F] & 0x07u) == 0u &&
                _ram_buff[0x43] == old_year &&
                _ram_buff[0x44] == old_mon &&
                _ram_buff[0x45] == old_day &&
                _ram_buff[0x6B] == 0u &&
                (_ram_buff[0xC7] & 0x80u) == 0u) {
                _ram_buff[0x6B] = 0xFFu;
                _ram_buff[0xC7] = 0x80u | 0x1Bu;
            }
        }
    }
}

static void sync_time(void) {
    time_t now_sec = 0;
    time(&now_sec);
    struct tm tm_val;
    memset(&tm_val, 0, sizeof(tm_val));
    if (_has_tz_offset) {
        time_t shifted = now_sec + (time_t) _tz_offset_sec;
        gmtime_r(&shifted, &tm_val);
    } else {
        tzset();
        localtime_r(&now_sec, &tm_val);
    }
    apply_nc1020_datetime(1900 + tm_val.tm_year,
                          tm_val.tm_mon + 1,
                          tm_val.tm_mday,
                          tm_val.tm_wday,
                          tm_val.tm_hour,
                          tm_val.tm_min,
                          tm_val.tm_sec);
}

void sync_nc1020_time(int year, int month, int day, int wday, int hour, int minute, int second, int32_t tz_offset_sec) {
    _tz_offset_sec = tz_offset_sec;
    _has_tz_offset = true;
    apply_nc1020_datetime(year, month, day, wday, hour, minute, second);
}

void initialize(const char *rom_file_path, const char *nor_file_path, const char *state_file_path) {
    strncpy(_rom_file_path, rom_file_path, MAX_FILE_NAME_LENGTH);
    strncpy(_nor_file_path, nor_file_path, MAX_FILE_NAME_LENGTH);
    strncpy(_state_file_path, state_file_path, MAX_FILE_NAME_LENGTH);

    _ram_buff = _nc1020_states.ram;
    _ram_page0 = _ram_buff;
    _ram_page2 = _ram_buff + 0x4000;
    _ram_page3 = _ram_buff + 0x6000;
    _clock_buff = _nc1020_states.clock_data;
    _jg_wav_buff = _nc1020_states.jg_wav_data;
    _fp_buff = _nc1020_states.fp_buff;
    _keypad_matrix = _nc1020_states.keypad_matrix;

	for (uint64_t i=0; i<0x20; i++) {
		_nor_banks[i] = _nor_buff + (0x8000 * i);
	}

    init_6502(peek_byte, load, store);
    init_nc1020_io(&_nc1020_states, _rom_buff, _nor_buff, _memmap);
    dsp_init();
    sound_init();

    load_rom();
}

static void reset_states(){
	_nc1020_states.version = VERSION;

	memset(_ram_buff, 0, 0x8000);
	_memmap[0] = _ram_page0;
	_memmap[2] = _ram_page2;
    switch_volume();

	memset(_keypad_matrix, 0, 8);

	memset(_clock_buff, 0, 80);
	_nc1020_states.clock_flags = 0;

	_nc1020_states.timer0_toggle = false;

	memset(_jg_wav_buff, 0, 0x20);
	_nc1020_states.jg_wav_flags = 0;
	_nc1020_states.jg_wav_idx = 0;

	_nc1020_states.should_wake_up = false;
	_nc1020_states.pending_wake_up = false;

	memset(_fp_buff, 0, 0x100);
	_nc1020_states.fp_step = 0;

	_nc1020_states.should_irq = false;

	_nc1020_states.cycles = 0;
	_nc1020_states.cpu.reg_a = 0;
	_nc1020_states.cpu.reg_ps = 0x24;
	_nc1020_states.cpu.reg_x = 0;
	_nc1020_states.cpu.reg_y = 0;
	_nc1020_states.cpu.reg_sp = 0xFF;
	_nc1020_states.cpu.reg_pc = peek_word(RESET_VEC);
	_nc1020_states.timer0_cycles = CYCLES_TIMER0;
	_nc1020_states.timer1_cycles = CYCLES_TIMER1;

	_tm0_running = false;
	_tm0_slice_deadline = 0;
	_current_slice_cycles = 0;
	_pending_cold_boot_sync = true;
	dsp_reset();
	sound_reset(false);
}

static void load_states(){
    reset_states();
	FILE* file = fopen(_state_file_path, "rbe");
	if (file == NULL) {
		return;
	}
	fread(&_nc1020_states, 1, sizeof(_nc1020_states), file);
	fclose(file);
	if (_nc1020_states.version != VERSION) {
		return;
	}
    if (_ram_buff[0x0460] == 0xA5u &&
        (_ram_buff[0x0472] | _ram_buff[0x0473] | _ram_buff[0x0474] | _ram_buff[0x0475]) != 0) {
        _pending_cold_boot_sync = false;
    }
    switch_volume();
    _ram_buff[0x0462] |= 0x80u;
    dsp_post_load_states((_ram_buff[0x20] & 0x80u) != 0 || !(_ram_buff[0x0493] & 0x80u));
    sound_reset((_ram_buff[0x18] & 0x80u) != 0);
    if ((_ram_buff[0x0492] & 0x80u) || (_ram_buff[0x0493] & 0x80u)) {
        _current_slice_cycles = 0;
        nc1020_tm0_start(_ram_buff[0x02]);
    }
}

static void save_states(){
	FILE* file = fopen(_state_file_path, "wbe");
	fwrite(&_nc1020_states, 1, sizeof(_nc1020_states), file);
	fflush(file);
	fclose(file);
}

void reset() {
    load_nor();
    reset_states();
}

void load_nc1020(){
    load_nor();
    load_states();
    sync_time();
}

void save_nc1020(){
    save_nor();
    save_states();
}

void set_key(uint8_t key_id, bool down_or_up){
	uint8_t row = (uint8_t) (key_id % 8u);
	uint8_t col = (uint8_t) (key_id / 8u);
	uint8_t bits = (uint8_t) (1u << col);
	if (key_id == 0x0F) {
		bits = 0xFE;
	}
	if (down_or_up) {
		_keypad_matrix[row] |= bits;
	} else {
		_keypad_matrix[row] &= ~bits;
	}

	// Align the kernel's cyclic keypad row index ($BA) so the next 256 Hz timer1 IRQ
	// scans the changed row immediately (~1.9 ms average) instead of up to 9 IRQs (~35 ms).
	if (!_nc1020_states.slept && _ram_buff[0x0460] == 0xA5u && !(_ram_buff[0xC7] & 0x80u)) {
		if (key_id == 0x0F) {
			_ram_buff[0xBA] = 6u;
		} else if (key_id < 0x40u) {
			uint8_t scan_idx = (uint8_t) (7u - row);
			_ram_buff[0xBA] = (scan_idx == 0u) ? 0xFFu : (uint8_t) (scan_idx - 1u);
		}
	}

	if (down_or_up) {
		if (_nc1020_states.slept) {
			if (key_id >= 0x08 && key_id <= 0x0F && key_id != 0x0E) {
                switch (key_id) {
                    case 0x08: _nc1020_states.wake_up_flags = 0x00; break;
                    case 0x09: _nc1020_states.wake_up_flags = 0x0A; break;
                    case 0x0A: _nc1020_states.wake_up_flags = 0x08; break;
                    case 0x0B: _nc1020_states.wake_up_flags = 0x06; break;
                    case 0x0C: _nc1020_states.wake_up_flags = 0x04; break;
                    case 0x0D: _nc1020_states.wake_up_flags = 0x02; break;
                    case 0x0E: _nc1020_states.wake_up_flags = 0x0C; break;
                    case 0x0F: _nc1020_states.wake_up_flags = 0x00; break;
                    default:break;
                }
				_nc1020_states.should_wake_up = true;
				_nc1020_states.pending_wake_up = true;
				_nc1020_states.slept = false;
			}
		} else {
			if (key_id == 0x0F) {
				_nc1020_states.slept = true;
			}
		}
	}
}

uint64_t get_cycles() {
    return _nc1020_states.cycles;
}

/**
 * @return The LCD buffer, size is 1600 uint_8
 */
uint8_t* get_lcd_buffer(){
    if (_nc1020_states.lcd_addr == 0)
        return NULL;

    uint8_t *lcd_buffer = _ram_buff + _nc1020_states.lcd_addr;
    return lcd_buffer;
}


size_t read_nc1020_audio(int16_t *buffer, size_t max_samples) {
    return read_audio_samples(buffer, max_samples);
}

void run_time_slice(uint64_t time_slice, bool speed_up) {
    uint64_t end_cycles = time_slice * CYCLES_MS;

    uint64_t cycles = 0;
    _current_slice_cycles = 0;

    sound_begin_slice(_nc1020_states.cycles, speed_up);

	while (cycles < end_cycles) {
		uint64_t step_cycles = execute_6502(&_nc1020_states.cpu);
		cycles += step_cycles;
		_current_slice_cycles = cycles;
		_nc1020_states.cycles += step_cycles;

		if (_tm0_running && cycles >= _tm0_slice_deadline) {
			_tm0_slice_deadline = cycles + tm0_cycles_for_counter(0);
			_ram_buff[0x02] = 0;
			_ram_buff[0x01] |= 0x10u;
			_nc1020_states.should_irq = true;
		}
		if (cycles >= _nc1020_states.timer0_cycles) {
			_nc1020_states.timer0_cycles += CYCLES_TIMER0;
			_nc1020_states.timer0_toggle = !_nc1020_states.timer0_toggle;
			if (!_nc1020_states.timer0_toggle) {
                adjust_time();
			}
			if (!is_count_down() || _nc1020_states.timer0_toggle) {
				write_io(0x3D, 0);
			} else {
                write_io(0x3D, 0x20);
				_nc1020_states.clock_flags &= 0xFD;
			}
			_nc1020_states.should_irq = true;
		}
		if (_nc1020_states.should_irq && !(_nc1020_states.cpu.reg_ps & 0x04u)) {
			_nc1020_states.should_irq = false;
			uint64_t irq_cycles = do_irq(&_nc1020_states.cpu);
			cycles += irq_cycles;
			_current_slice_cycles = cycles;
			_nc1020_states.cycles += irq_cycles;
		}
		if (cycles >= _nc1020_states.timer1_cycles) {
			if (speed_up) {
				_nc1020_states.timer1_cycles += CYCLES_TIMER1_SPEED_UP;
			} else {
				_nc1020_states.timer1_cycles += CYCLES_TIMER1;
			}
			_clock_buff[4] ++;
			if (_nc1020_states.should_wake_up) {
				_nc1020_states.should_wake_up = false;
				_nc1020_states.should_irq = false;
				_ram_buff[0x01] |= 0x01u;
				_ram_buff[0x02] |= 0x01u;
				_nc1020_states.cpu.reg_pc = peek_word(RESET_VEC);
			} else {
				_ram_buff[0x01] |= 0x08u;
				_nc1020_states.should_irq = true;
			}
		}
	}

	sound_end_slice(_nc1020_states.cycles);

	_current_slice_cycles = 0;
	if (_tm0_running) {
		_tm0_slice_deadline = (_tm0_slice_deadline > cycles) ? (_tm0_slice_deadline - cycles) : 0;
	}
	_nc1020_states.timer0_cycles -= end_cycles;
	_nc1020_states.timer1_cycles -= end_cycles;
}
