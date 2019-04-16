#include "nc1020.h"
#include "cpu6502.h"
#include "nc1020_states.h"
#include "nc1020_io.h"
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
    switch_volume();
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

void sync_time() {
    time_t time_raw_format;
    struct tm * ptr_time;
    time ( &time_raw_format );
    ptr_time = localtime ( &time_raw_format );
    store(1138, (uint8_t) (1900 + ptr_time -> tm_year - 1881));
    store(1139, (uint8_t) (ptr_time -> tm_mon + 1));
    store(1140, (uint8_t) (ptr_time -> tm_mday + 1));
    store(1141, (uint8_t) (ptr_time -> tm_wday));
    store(1135, (uint8_t) (ptr_time -> tm_hour));
    store(1136, (uint8_t) (ptr_time -> tm_min));
    store(1137, (uint8_t) (ptr_time -> tm_sec / 2));
}

uint64_t get_cycles() {
    return _nc1020_states.cycles;
}

bool copy_lcd_buffer(uint8_t *buffer){
	if (_nc1020_states.lcd_addr == 0) return false;
	memcpy(buffer, _ram_buff + _nc1020_states.lcd_addr, 1600);
	return true;
}

void run_time_slice(uint64_t time_slice, bool speed_up) {
	uint64_t end_cycles = time_slice * CYCLES_MS;

    uint64_t cycles = 0;

	while (cycles < end_cycles) {
		cycles += execute_6502(&_nc1020_states.cpu);
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
		if (_nc1020_states.should_irq) {
			_nc1020_states.should_irq = false;
			cycles += do_irq(&_nc1020_states.cpu);
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
                write_io(0x01, (uint8_t) (read_io(0x01) | 0x01u));
                write_io(0x02, (uint8_t) (read_io(0x02) | 0x01u));
				_nc1020_states.cpu.reg_pc = peek_word(RESET_VEC);
			} else {
                write_io(0x01, (uint8_t) (read_io(0x01) | 0x08u));
				_nc1020_states.should_irq = true;
			}
		}
	}

	_nc1020_states.cycles += cycles;
	_nc1020_states.timer0_cycles -= end_cycles;
	_nc1020_states.timer1_cycles -= end_cycles;
}
