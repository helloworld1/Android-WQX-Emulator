#include "nc1020.h"
#include "cpu6502.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>

// cpu cycles per second (cpu freq).
const unsigned long CYCLES_SECOND = 5120000;
const unsigned long TIMER0_FREQ = 2;
const unsigned long TIMER1_FREQ = 0x100;
// cpu cycles per timer0 period (1/2 s).
const unsigned long CYCLES_TIMER0 = CYCLES_SECOND / TIMER0_FREQ;
// cpu cycles per timer1 period (1/256 s).
const unsigned long CYCLES_TIMER1 = CYCLES_SECOND / TIMER1_FREQ;
// speed up
const unsigned long CYCLES_TIMER1_SPEED_UP = CYCLES_SECOND / TIMER1_FREQ / 20;
// cpu cycles per ms (1/1000 s).
const unsigned long CYCLES_MS = CYCLES_SECOND / 1000;

static const unsigned long ROM_SIZE = 0x8000 * 0x300;
static const unsigned long NOR_SIZE = 0x8000 * 0x20;

static const uint16_t IO_LIMIT = 0x40;
#define IO_API
typedef uint8_t (IO_API *io_read_func_t)(uint8_t);
typedef void (IO_API *io_write_func_t)(uint8_t, uint8_t);

const uint16_t NMI_VEC = 0xFFFA;
const uint16_t RESET_VEC = 0xFFFC;
const uint16_t IRQ_VEC = 0xFFFE;

const unsigned long VERSION = 0x06;

static const char *ROM_FILE_NAME = "obj_lu.bin";
static const char *NOR_FILE_NAME = "nc1020.fls";
static const char *STATE_FILE_NAME = "nc1020.sts";

static const int MAX_FILE_NAME_LENGTH = 255;

typedef struct {
	unsigned long version;
	cpu_states_t cpu;
	uint8_t ram[0x8000];

	uint8_t bak_40[0x40];

	uint8_t clock_data[80];
	uint8_t clock_flags;

	uint8_t jg_wav_data[0x20];
	uint8_t jg_wav_flags;
	uint8_t jg_wav_idx;
	bool jg_wav_playing;

	uint8_t fp_step;
	uint8_t fp_type;
	uint8_t fp_bank_idx;
	uint8_t fp_bak1;
	uint8_t fp_bak2;
	uint8_t fp_buff[0x100];

	bool slept;
	bool should_wake_up;
	bool pending_wake_up;
	uint8_t wake_up_flags;

	bool timer0_toggle;
	unsigned long cycles;
	unsigned long timer0_cycles;
	unsigned long timer1_cycles;
	bool should_irq;

	unsigned long lcd_addr;
	uint8_t keypad_matrix[8];
} nc1020_states_t;

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
static uint8_t* stack;
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

static io_read_func_t io_read[0x40];
static io_write_func_t io_write[0x40];

uint8_t* GetBank(uint8_t bank_idx){
	uint8_t volume_idx = ram_io[0x0D];
    if (bank_idx < 0x20) {
    	return nor_banks[bank_idx];
    } else if (bank_idx >= 0x80) {
        if (volume_idx & 0x01u) {
        	return rom_volume1[bank_idx];
        } else if (volume_idx & 0x02u) {
        	return rom_volume2[bank_idx];
        } else {
        	return rom_volume0[bank_idx];
        }
    }
    return NULL;
}

void SwitchBank(){
	uint8_t bank_idx = ram_io[0x00];
	uint8_t* bank = GetBank(bank_idx);
    memmap[2] = bank;
    memmap[3] = bank + 0x2000;
    memmap[4] = bank + 0x4000;
    memmap[5] = bank + 0x6000;
}

uint8_t** GetVolumm(uint8_t volume_idx){
	if ((volume_idx & 0x03u) == 0x01) {
		return rom_volume1;
	} else if ((volume_idx & 0x03u) == 0x03) {
		return rom_volume2;
	} else {
		return rom_volume0;
	}
}

void SwitchVolume(){
	uint8_t volume_idx = ram_io[0x0D];
    uint8_t** volume = GetVolumm(volume_idx);
    for (int i=0; i<4; i++) {
        bbs_pages[i * 4] = volume[i];
        bbs_pages[i * 4 + 1] = volume[i] + 0x2000;
        bbs_pages[i * 4 + 2] = volume[i] + 0x4000;
        bbs_pages[i * 4 + 3] = volume[i] + 0x6000;
    }
    bbs_pages[1] = ram_page3;
    memmap[7] = volume[0] + 0x2000;
    uint8_t roa_bbs = ram_io[0x0A];
    memmap[1] = (roa_bbs & 0x04u ? ram_page2 : ram_page1);
    memmap[6] = bbs_pages[roa_bbs & 0x0Fu];
    SwitchBank();
}

void GenerateAndPlayJGWav(){

}

uint8_t* GetPtr40(uint8_t index){
    if (index < 4) {
        return ram_io;
    } else {
        return ram_buff + ((index) << 6u);
    }
}

uint8_t IO_API ReadXX(uint8_t addr){
	return ram_io[addr];
}

uint8_t IO_API Read06(uint8_t addr){
	return ram_io[addr];
}

uint8_t IO_API Read3B(uint8_t addr){
    if (!(ram_io[0x3D] & 0x03u)) {
        return (uint8_t) (clock_buff[0x3Bu] & 0xFEu);
    }
    return ram_io[addr];
}

uint8_t IO_API Read3F(uint8_t addr){
    uint8_t idx = ram_io[0x3E];
    return (uint8_t) (idx < 80 ? clock_buff[idx] : 0);
}

void IO_API WriteXX(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
}


// switch bank.
void IO_API Write00(uint8_t addr, uint8_t value){
    uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    if (value != old_value) {
    	SwitchBank();
    }
}

void IO_API Write05(uint8_t addr, uint8_t value){
	uint8_t old_value = ram_io[addr];
	ram_io[addr] = value;
	if ((old_value ^ value) & 0x08u) {
		nc1020_states.slept = !(value & 0x08u);
	}
}

void IO_API Write06(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    if (!nc1020_states.lcd_addr) {
    	nc1020_states.lcd_addr = ((ram_io[0x0C] & 0x03u) << 12u) | (value << 4u);
    }
    ram_io[0x09] &= 0xFEu;
}

void IO_API Write08(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    ram_io[0x0B] &= 0xFEu;
}

// keypad matrix.
void IO_API Write09(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    switch (value){
    case 0x01: ram_io[0x08] = keypad_matrix[0]; break;
    case 0x02: ram_io[0x08] = keypad_matrix[1]; break;
    case 0x04: ram_io[0x08] = keypad_matrix[2]; break;
    case 0x08: ram_io[0x08] = keypad_matrix[3]; break;
    case 0x10: ram_io[0x08] = keypad_matrix[4]; break;
    case 0x20: ram_io[0x08] = keypad_matrix[5]; break;
    case 0x40: ram_io[0x08] = keypad_matrix[6]; break;
    case 0x80: ram_io[0x08] = keypad_matrix[7]; break;
    case 0:
        ram_io[0x0B] |= 1u;
        if (keypad_matrix[7] == 0xFE) {
            ram_io[0x0B] &= 0xFEu;
        }
        break;
    case 0x7F:
        if (ram_io[0x15] == 0x7Fu) {
            ram_io[0x08] = keypad_matrix[0] |
                           keypad_matrix[1] |
                           keypad_matrix[2] |
                           keypad_matrix[3] |
                           keypad_matrix[4] |
                           keypad_matrix[5] |
                           keypad_matrix[6] |
                           keypad_matrix[7];
        }
        break;
        default:break;
    }
}

// roabbs
void IO_API Write0A(uint8_t addr, uint8_t value){
    uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    if (value != old_value) {
        memmap[6] = bbs_pages[value & 0x0Fu];
    }
}

// switch volume
void IO_API Write0D(uint8_t addr, uint8_t value){
	uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    if (value != old_value) {
        SwitchVolume();
    }
}

// zp40 switch
void IO_API Write0F(uint8_t addr, uint8_t value){
	uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    old_value &= 0x07u;
    value &= 0x07u;
    if (value != old_value) {
        uint8_t* ptr_new = GetPtr40(value);
        if (old_value) {
            memcpy(GetPtr40(old_value), ram_40, 0x40);
            memcpy(ram_40, value ? ptr_new : bak_40, 0x40);
        } else {
            memcpy(bak_40, ram_40, 0x40);
            memcpy(ram_40, ptr_new, 0x40);
        }
    }
}

void IO_API Write20(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    if (value == 0x80 || value == 0x40) {
        memset(jg_wav_buff, 0, 0x20);
        ram_io[0x20] = 0;
        nc1020_states.jg_wav_flags = 1;
        nc1020_states.jg_wav_idx= 0;
    }
}

void IO_API Write23(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    if (value == 0xC2) {
        jg_wav_buff[nc1020_states.jg_wav_idx] = ram_io[0x22];
    } else if (value == 0xC4) {
        if (nc1020_states.jg_wav_idx < 0x20) {
            jg_wav_buff[nc1020_states.jg_wav_idx] = ram_io[0x22];
            nc1020_states.jg_wav_idx ++;
        }
    } else if (value == 0x80) {
        ram_io[0x20] = 0x80;
        nc1020_states.jg_wav_flags = 0;
        if (nc1020_states.jg_wav_idx) {
            if (!nc1020_states.jg_wav_playing) {
                GenerateAndPlayJGWav();
                nc1020_states.jg_wav_idx = 0;
            }
        }
    }
    if (nc1020_states.jg_wav_playing) {
        // todo.
    }
}

// clock.
void IO_API Write3F(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    uint8_t idx = ram_io[0x3E];
    if (idx >= 0x07) {
        if (idx == 0x0B) {
            ram_io[0x3D] = 0xF8;
            nc1020_states.clock_flags |= value & 0x07u;
            clock_buff[0x0B] = (uint8_t) (value ^ ((clock_buff[0x0B] ^ value) & 0x7Fu));
        } else if (idx == 0x0A) {
            nc1020_states.clock_flags |= value & 0x07u;
            clock_buff[0x0A] = value;
        } else {
            clock_buff[idx % 80] = value;
        }
    } else {
        if (!(clock_buff[0x0B] & 0x80u) && idx < 80u) {
            clock_buff[idx] = value;
        }
    }
}

void AdjustTime(){
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

bool IsCountDown(){
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
void ProcessBinary(uint8_t* dest, uint8_t* src, unsigned long size){
	unsigned long offset = 0;
    while (offset < size) {
        memcpy(dest + offset + 0x4000, src + offset, 0x4000);
        memcpy(dest + offset, src + offset + 0x4000, 0x4000);
        offset += 0x8000;
    }
}

void LoadRom(){
	uint8_t* temp_buff = (uint8_t*)malloc(ROM_SIZE);
	FILE* file = fopen(rom_file_path, "rbe");
	fread(temp_buff, 1, ROM_SIZE, file);
	ProcessBinary(rom_buff, temp_buff, ROM_SIZE);
	free(temp_buff);
	fclose(file);
}

void LoadNor(){
	uint8_t* temp_buff = (uint8_t*)malloc(NOR_SIZE);
	FILE* file = fopen(nor_file_path, "rbe");
	fread(temp_buff, 1, NOR_SIZE, file);
	ProcessBinary(nor_buff, temp_buff, NOR_SIZE);
	free(temp_buff);
	fclose(file);
}

void SaveNor(){
	uint8_t* temp_buff = (uint8_t*)malloc(NOR_SIZE);
	FILE* file = fopen(nor_file_path, "wbe");
	ProcessBinary(temp_buff, nor_buff, NOR_SIZE);
	fwrite(temp_buff, 1, NOR_SIZE, file);
	fflush(file);
	free(temp_buff);
	fclose(file);
}

uint8_t peek_byte(uint16_t addr) {
	return memmap[addr / 0x2000][addr % 0x2000];
}

uint16_t peek_word(uint16_t addr) {
	return peek_byte(addr) | (peek_byte((uint16_t) (addr + 1u)) << 8u);
}
uint8_t load(uint16_t addr) {
	if (addr < IO_LIMIT) {
		return io_read[addr]((uint8_t) addr);
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

void store(uint16_t addr, uint8_t value) {
	if (addr < IO_LIMIT) {
		io_write[addr]((uint8_t) addr, value);
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
        	for (unsigned long i=0; i<0x20; i++) {
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

void Initialize(const char* path) {
    snprintf(rom_file_path, MAX_FILE_NAME_LENGTH, "%s/%s", path, ROM_FILE_NAME);
    snprintf(nor_file_path, MAX_FILE_NAME_LENGTH, "%s/%s", path, NOR_FILE_NAME);
    snprintf(state_file_path, MAX_FILE_NAME_LENGTH, "%s/%s", path, STATE_FILE_NAME);

    ram_buff = nc1020_states.ram;
    stack = ram_buff + 0x100;
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

	for (unsigned long i=0; i<0x100; i++) {
		rom_volume0[i] = rom_buff + (0x8000 * i);
		rom_volume1[i] = rom_buff + (0x8000 * (0x100 + i));
		rom_volume2[i] = rom_buff + (0x8000 * (0x200 + i));
	}
	for (unsigned long i=0; i<0x20; i++) {
		nor_banks[i] = nor_buff + (0x8000 * i);
	}
	for (unsigned long i=0; i<0x40; i++) {
		io_read[i] = ReadXX;
		io_write[i] = WriteXX;
	}
	io_read[0x06] = Read06;
	io_read[0x3B] = Read3B;
	io_read[0x3F] = Read3F;
	io_write[0x00] = Write00;
	io_write[0x05] = Write05;
	io_write[0x06] = Write06;
	io_write[0x08] = Write08;
	io_write[0x09] = Write09;
	io_write[0x0A] = Write0A;
	io_write[0x0D] = Write0D;
	io_write[0x0F] = Write0F;
	io_write[0x20] = Write20;
	io_write[0x23] = Write23;
	io_write[0x3F] = Write3F;

	init_6502(peek_byte, load, store);

	LoadRom();
}

void ResetStates(){
	nc1020_states.version = VERSION;

	memset(ram_buff, 0, 0x8000);
	memmap[0] = ram_page0;
	memmap[2] = ram_page2;
	SwitchVolume();

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

void Reset() {
	LoadNor();
	ResetStates();
}

void LoadStates(){
	ResetStates();
	FILE* file = fopen(state_file_path, "rbe");
	if (file == NULL) {
		return;
	}
	fread(&nc1020_states, 1, sizeof(nc1020_states), file);
	fclose(file);
	if (nc1020_states.version != VERSION) {
		return;
	}
	SwitchVolume();
}

void SaveStates(){
	FILE* file = fopen(state_file_path, "wbe");
	fwrite(&nc1020_states, 1, sizeof(nc1020_states), file);
	fflush(file);
	fclose(file);
}

void LoadNC1020(){
	LoadNor();
	LoadStates();
}

void SaveNC1020(){
	SaveNor();
	SaveStates();
}

void SetKey(uint8_t key_id, bool down_or_up){
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

bool CopyLcdBuffer(uint8_t* buffer){
	if (nc1020_states.lcd_addr == 0) return false;
	memcpy(buffer, ram_buff + nc1020_states.lcd_addr, 1600);
	return true;
}

void RunTimeSlice(unsigned long time_slice, bool speed_up) {
	unsigned long end_cycles = time_slice * CYCLES_MS;
	unsigned long cycles = nc1020_states.cycles;

	while (cycles < end_cycles) {
		cycles += execute_6502(&nc1020_states.cpu);
		if (cycles >= nc1020_states.timer0_cycles) {
			nc1020_states.timer0_cycles += CYCLES_TIMER0;
			nc1020_states.timer0_toggle = !nc1020_states.timer0_toggle;
			if (!nc1020_states.timer0_toggle) {
				AdjustTime();
			}
			if (!IsCountDown() || nc1020_states.timer0_toggle) {
				ram_io[0x3D] = 0;
			} else {
				ram_io[0x3D] = 0x20;
				nc1020_states.clock_flags &= 0xFD;
			}
			nc1020_states.should_irq = true;
		}
		if (nc1020_states.should_irq && !(nc1020_states.cpu.reg_ps & 0x04)) {
			nc1020_states.should_irq = false;
			stack[nc1020_states.cpu.reg_sp --] = nc1020_states.cpu.reg_pc >> 8;
			stack[nc1020_states.cpu.reg_sp --] = nc1020_states.cpu.reg_pc & 0xFF;
			nc1020_states.cpu.reg_ps &= 0xEF;
			stack[nc1020_states.cpu.reg_sp --] = nc1020_states.cpu.reg_ps;
			nc1020_states.cpu.reg_pc = peek_word(IRQ_VEC);
			nc1020_states.cpu.reg_ps |= 0x04;
			cycles += 7;
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

	cycles -= end_cycles;
	nc1020_states.timer0_cycles -= end_cycles;
	nc1020_states.timer1_cycles -= end_cycles;
}
