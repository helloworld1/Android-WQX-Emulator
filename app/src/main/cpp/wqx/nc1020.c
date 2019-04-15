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

static const char *ROM_FILE_NAME = "obj_lu.bin";
static const char *NOR_FILE_NAME = "nc1020.fls";
static const char *STATE_FILE_NAME = "nc1020.sts";

static const int MAX_FILE_NAME_LENGTH = 255;

static char rom_file_path[MAX_FILE_NAME_LENGTH];
static char nor_file_path[MAX_FILE_NAME_LENGTH];
static char state_file_path[MAX_FILE_NAME_LENGTH];

static uint8_t rom_buff[ROM_SIZE];
static uint8_t nor_buff[NOR_SIZE];

static uint8_t* rom_volume0[0x100];
static uint8_t* rom_volume1[0x100];
static uint8_t* rom_volume2[0x100];

static uint8_t* nor_banks[0x20];
static uint8_t* bbs_pages[0x10];

static uint8_t* memmap[8];
static nc1020_states_t nc1020_states;

static uint8_t* ram_buff;
static uint8_t* ram_io;
static uint8_t* ram_40;
static uint8_t* ram_page0;
static uint8_t* ram_page1;
static uint8_t* ram_page2;
static uint8_t* ram_page3;

static uint8_t* clock_buff;

static uint8_t* jg_wav_buff;

static uint8_t* bak_40;
static uint8_t* fp_buff;

static uint8_t* keypad_matrix;

static void adjust_time(){
    if (++ clock_buff[0] >= 60) {
        clock_buff[0] = 0;
        if (++ clock_buff[1] >= 60) {
            clock_buff[1] = 0;
            if (++ clock_buff[2] >= 24) {
                clock_buff[2] &= 0xC0u;
                ++ clock_buff[3];
            }
        }
    }
}

static bool is_count_down(){
    if (!(clock_buff[10] & 0x02u) ||
        !(nc1020_states.clock_flags & 0x02u)) {
        return false;
    }
    return (
        ((clock_buff[7] & 0x80u) && !(((clock_buff[7] ^ clock_buff[2])) & 0x1Fu)) ||
        ((clock_buff[6] & 0x80u) && !(((clock_buff[6] ^ clock_buff[1])) & 0x3Fu)) ||
        ((clock_buff[5] & 0x80u) && !(((clock_buff[5] ^ clock_buff[0])) & 0x3Fu))
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
	FILE* file = fopen(rom_file_path, "rbe");
	fread(temp_buff, 1, ROM_SIZE, file);
    process_binary(rom_buff, temp_buff, ROM_SIZE);
	free(temp_buff);
	fclose(file);
}

static void load_nor(){
	uint8_t* temp_buff = (uint8_t*)malloc(NOR_SIZE);
	FILE* file = fopen(nor_file_path, "rbe");
	fread(temp_buff, 1, NOR_SIZE, file);
    process_binary(nor_buff, temp_buff, NOR_SIZE);
	free(temp_buff);
	fclose(file);
}

static void save_nor(){
	uint8_t* temp_buff = (uint8_t*)malloc(NOR_SIZE);
	FILE* file = fopen(nor_file_path, "wbe");
    process_binary(temp_buff, nor_buff, NOR_SIZE);
	fwrite(temp_buff, 1, NOR_SIZE, file);
	fflush(file);
	free(temp_buff);
	fclose(file);
}

static uint8_t peek_byte(uint16_t addr) {
	return memmap[addr / 0x2000][addr % 0x2000];
}

static uint16_t peek_word(uint16_t addr) {
	return peek_byte(addr) | (peek_byte((uint16_t) (addr + 1u)) << 8u);
}
static uint8_t load(uint16_t addr) {
	if (addr < IO_LIMIT) {
		return read_io((uint8_t) addr);
	}
	if (((nc1020_states.fp_step == 4 && nc1020_states.fp_type == 2) ||
		(nc1020_states.fp_step == 6 && nc1020_states.fp_type == 3)) &&
		(addr >= 0x4000 && addr < 0xC000)) {
		nc1020_states.fp_step = 0;
		return 0x88;
	}
	if (addr == 0x45F && nc1020_states.pending_wake_up) {
		nc1020_states.pending_wake_up = false;
		memmap[0][0x45F] = nc1020_states.wake_up_flags;
	}
	return peek_byte(addr);
}

static void store(uint16_t addr, uint8_t value) {
	if (addr < IO_LIMIT) {
		write_io((uint8_t) addr, value);
		return;
	}
	if (addr < 0x4000) {
        memmap[addr / 0x2000][addr % 0x2000] = value;
		return;
	}
	uint8_t* page = memmap[addr >> 13u];
	if (page == ram_page2 || page == ram_page3) {
		page[addr & 0x1FFFu] = value;
		return;
	}
	if (addr >= 0xE000) {
		return;
	}

    // write to nor_flash address space.
    // there must select a nor_bank.

    uint8_t bank_idx = ram_io[0x00];
    if (bank_idx >= 0x20) {
        return;
    }

    uint8_t* bank = nor_banks[bank_idx];

    if (nc1020_states.fp_step == 0) {
        if (addr == 0x5555 && value == 0xAA) {
            nc1020_states.fp_step = 1;
        }
        return;
    }
    if (nc1020_states.fp_step == 1) {
        if (addr == 0xAAAA && value == 0x55) {
        	nc1020_states.fp_step = 2;
            return;
        }
    } else if (nc1020_states.fp_step == 2) {
        if (addr == 0x5555) {
            switch (value) {
                case 0x90: nc1020_states.fp_type = 1; break;
                case 0xA0: nc1020_states.fp_type = 2; break;
                case 0x80: nc1020_states.fp_type = 3; break;
                case 0xA8: nc1020_states.fp_type = 4; break;
                case 0x88: nc1020_states.fp_type = 5; break;
                case 0x78: nc1020_states.fp_type = 6; break;
                default:break;
            }
            if (nc1020_states.fp_type) {
                if (nc1020_states.fp_type == 1) {
                    nc1020_states.fp_bank_idx = bank_idx;
                    nc1020_states.fp_bak1 = bank[0x4000];
                    nc1020_states.fp_bak1 = bank[0x4001];
                }
                nc1020_states.fp_step = 3;
                return;
            }
        }
    } else if (nc1020_states.fp_step == 3) {
        if (nc1020_states.fp_type == 1) {
            if (value == 0xF0) {
                bank[0x4000] = nc1020_states.fp_bak1;
                bank[0x4001] = nc1020_states.fp_bak2;
                nc1020_states.fp_step = 0;
                return;
            }
        } else if (nc1020_states.fp_type == 2) {
            bank[addr - 0x4000] &= value;
            nc1020_states.fp_step = 4;
            return;
        } else if (nc1020_states.fp_type == 4) {
            fp_buff[addr & 0xFFu] &= value;
            nc1020_states.fp_step = 4;
            return;
        } else if (nc1020_states.fp_type == 3 || nc1020_states.fp_type == 5) {
            if (addr == 0x5555 && value == 0xAA) {
                nc1020_states.fp_step = 4;
                return;
            }
        }
    } else if (nc1020_states.fp_step == 4) {
        if (nc1020_states.fp_type == 3 || nc1020_states.fp_type == 5) {
            if (addr == 0xAAAA && value == 0x55) {
                nc1020_states.fp_step = 5;
                return;
            }
        }
    } else if (nc1020_states.fp_step == 5) {
        if (addr == 0x5555 && value == 0x10) {
        	for (uint64_t i=0; i<0x20; i++) {
                memset(nor_banks[i], 0xFF, 0x8000);
            }
            if (nc1020_states.fp_type == 5) {
                memset(fp_buff, 0xFF, 0x100);
            }
            nc1020_states.fp_step = 6;
            return;
        }
        if (nc1020_states.fp_type == 3) {
            if (value == 0x30) {
                memset(bank + (addr - (addr % 0x800) - 0x4000), 0xFF, 0x800);
                nc1020_states.fp_step = 6;
                return;
            }
        } else if (nc1020_states.fp_type == 5) {
            if (value == 0x48) {
                memset(fp_buff, 0xFF, 0x100);
                nc1020_states.fp_step = 6;
                return;
            }
        }
    }
    if (addr == 0x8000 && value == 0xF0) {
        nc1020_states.fp_step = 0;
        return;
    }
    printf("error occurs when operate in flash!");
}

void initialize(const char *path) {
    snprintf(rom_file_path, MAX_FILE_NAME_LENGTH, "%s/%s", path, ROM_FILE_NAME);
    snprintf(nor_file_path, MAX_FILE_NAME_LENGTH, "%s/%s", path, NOR_FILE_NAME);
    snprintf(state_file_path, MAX_FILE_NAME_LENGTH, "%s/%s", path, STATE_FILE_NAME);

    ram_buff = nc1020_states.ram;
    ram_io = ram_buff;
    ram_40 = ram_buff + 0x40;
    ram_page0 = ram_buff;
    ram_page1 = ram_buff + 0x2000;
    ram_page2 = ram_buff + 0x4000;
    ram_page3 = ram_buff + 0x6000;
    clock_buff = nc1020_states.clock_data;
    jg_wav_buff = nc1020_states.jg_wav_data;
    bak_40 = nc1020_states.bak_40;
    fp_buff = nc1020_states.fp_buff;
    keypad_matrix = nc1020_states.keypad_matrix;

	for (uint64_t i=0; i<0x100; i++) {
		rom_volume0[i] = rom_buff + (0x8000 * i);
		rom_volume1[i] = rom_buff + (0x8000 * (0x100 + i));
		rom_volume2[i] = rom_buff + (0x8000 * (0x200 + i));
	}
	for (uint64_t i=0; i<0x20; i++) {
		nor_banks[i] = nor_buff + (0x8000 * i);
	}


    init_6502(peek_byte, load, store);
    init_nc1020_io(&nc1020_states, rom_buff, nor_buff, memmap);

    load_rom();
}

static void reset_states(){
	nc1020_states.version = VERSION;

	memset(ram_buff, 0, 0x8000);
	memmap[0] = ram_page0;
	memmap[2] = ram_page2;
    switch_volume();

	memset(keypad_matrix, 0, 8);

	memset(clock_buff, 0, 80);
	nc1020_states.clock_flags = 0;

	nc1020_states.timer0_toggle = false;

	memset(jg_wav_buff, 0, 0x20);
	nc1020_states.jg_wav_flags = 0;
	nc1020_states.jg_wav_idx = 0;

	nc1020_states.should_wake_up = false;
	nc1020_states.pending_wake_up = false;

	memset(fp_buff, 0, 0x100);
	nc1020_states.fp_step = 0;

	nc1020_states.should_irq = false;

	nc1020_states.cycles = 0;
	nc1020_states.cpu.reg_a = 0;
	nc1020_states.cpu.reg_ps = 0x24;
	nc1020_states.cpu.reg_x = 0;
	nc1020_states.cpu.reg_y = 0;
	nc1020_states.cpu.reg_sp = 0xFF;
	nc1020_states.cpu.reg_pc = peek_word(RESET_VEC);
	nc1020_states.timer0_cycles = CYCLES_TIMER0;
	nc1020_states.timer1_cycles = CYCLES_TIMER1;
}

static void load_states(){
    reset_states();
	FILE* file = fopen(state_file_path, "rbe");
	if (file == NULL) {
		return;
	}
	fread(&nc1020_states, 1, sizeof(nc1020_states), file);
	fclose(file);
	if (nc1020_states.version != VERSION) {
		return;
	}
    switch_volume();
}

static void save_states(){
	FILE* file = fopen(state_file_path, "wbe");
	fwrite(&nc1020_states, 1, sizeof(nc1020_states), file);
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

void delete_state_and_nor() {
    remove(nor_file_path);
    remove(state_file_path);
}

void set_key(uint8_t key_id, bool down_or_up){
	uint8_t row = (uint8_t) (key_id % 8u);
	uint8_t col = (uint8_t) (key_id / 8u);
	uint8_t bits = (uint8_t) (1u << col);
	if (key_id == 0x0F) {
		bits = 0xFE;
	}
	if (down_or_up) {
		keypad_matrix[row] |= bits;
	} else {
		keypad_matrix[row] &= ~bits;
	}

	if (down_or_up) {
		if (nc1020_states.slept) {
			if (key_id >= 0x08 && key_id <= 0x0F && key_id != 0x0E) {
                switch (key_id) {
                    case 0x08: nc1020_states.wake_up_flags = 0x00; break;
                    case 0x09: nc1020_states.wake_up_flags = 0x0A; break;
                    case 0x0A: nc1020_states.wake_up_flags = 0x08; break;
                    case 0x0B: nc1020_states.wake_up_flags = 0x06; break;
                    case 0x0C: nc1020_states.wake_up_flags = 0x04; break;
                    case 0x0D: nc1020_states.wake_up_flags = 0x02; break;
                    case 0x0E: nc1020_states.wake_up_flags = 0x0C; break;
                    case 0x0F: nc1020_states.wake_up_flags = 0x00; break;
                    default:break;
                }
				nc1020_states.should_wake_up = true;
				nc1020_states.pending_wake_up = true;
				nc1020_states.slept = false;
			}
		} else {
			if (key_id == 0x0F) {
				nc1020_states.slept = true;
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
    return nc1020_states.cycles;
}

bool copy_lcd_buffer(uint8_t *buffer){
	if (nc1020_states.lcd_addr == 0) return false;
	memcpy(buffer, ram_buff + nc1020_states.lcd_addr, 1600);
	return true;
}

void run_time_slice(uint64_t time_slice, bool speed_up) {
	uint64_t end_cycles = time_slice * CYCLES_MS;

    uint64_t cycles = 0;

	while (cycles < end_cycles) {
		cycles += execute_6502(&nc1020_states.cpu);
		if (cycles >= nc1020_states.timer0_cycles) {
			nc1020_states.timer0_cycles += CYCLES_TIMER0;
			nc1020_states.timer0_toggle = !nc1020_states.timer0_toggle;
			if (!nc1020_states.timer0_toggle) {
                adjust_time();
			}
			if (!is_count_down() || nc1020_states.timer0_toggle) {
				ram_io[0x3D] = 0;
			} else {
				ram_io[0x3D] = 0x20;
				nc1020_states.clock_flags &= 0xFD;
			}
			nc1020_states.should_irq = true;
		}
		if (nc1020_states.should_irq) {
			nc1020_states.should_irq = false;
			cycles += do_irq(&nc1020_states.cpu);
		}
		if (cycles >= nc1020_states.timer1_cycles) {
			if (speed_up) {
				nc1020_states.timer1_cycles += CYCLES_TIMER1_SPEED_UP;
			} else {
				nc1020_states.timer1_cycles += CYCLES_TIMER1;
			}
			clock_buff[4] ++;
			if (nc1020_states.should_wake_up) {
				nc1020_states.should_wake_up = false;
				ram_io[0x01] |= 0x01;
				ram_io[0x02] |= 0x01;
				nc1020_states.cpu.reg_pc = peek_word(RESET_VEC);
			} else {
				ram_io[0x01] |= 0x08;
				nc1020_states.should_irq = true;
			}
		}
	}

	nc1020_states.cycles += cycles;
	nc1020_states.timer0_cycles -= end_cycles;
	nc1020_states.timer1_cycles -= end_cycles;
}
