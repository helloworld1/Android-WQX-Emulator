#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <time.h>
#include "nc1020_states.h"

static nc1020_states_t *nc1020_states;

static uint8_t* rom_volume0[0x100];
static uint8_t* rom_volume1[0x100];
static uint8_t* rom_volume2[0x100];

static uint8_t* nor_banks[0x20];
static uint8_t* bbs_pages[0x10];

static uint8_t** memmap;

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


void init_nc1020_io(nc1020_states_t *states, uint8_t rom_buff[], uint8_t nor_buff[], uint8_t* mmap[8]) {
    nc1020_states = states;

    ram_buff = nc1020_states -> ram;
    ram_io = ram_buff;
    ram_40 = ram_buff + 0x40;
    ram_page0 = ram_buff;
    ram_page1 = ram_buff + 0x2000;
    ram_page2 = ram_buff + 0x4000;
    ram_page3 = ram_buff + 0x6000;
    clock_buff = nc1020_states -> clock_data;
    jg_wav_buff = nc1020_states -> jg_wav_data;
    bak_40 = nc1020_states -> bak_40;
    fp_buff = nc1020_states -> fp_buff;
    keypad_matrix = nc1020_states -> keypad_matrix;
    memmap = mmap;

    for (uint64_t i=0; i<0x100; i++) {
        rom_volume0[i] = rom_buff + (0x8000 * i);
        rom_volume1[i] = rom_buff + (0x8000 * (0x100 + i));
        rom_volume2[i] = rom_buff + (0x8000 * (0x200 + i));
    }
    for (uint64_t i=0; i<0x20; i++) {
        nor_banks[i] = nor_buff + (0x8000 * i);
    }
}

static uint8_t* get_bank(uint8_t bank_idx){
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

static void switch_bank(){
    uint8_t bank_idx = ram_io[0x00];
    uint8_t* bank = get_bank(bank_idx);
    memmap[2] = bank;
    memmap[3] = bank + 0x2000;
    memmap[4] = bank + 0x4000;
    memmap[5] = bank + 0x6000;
}

static uint8_t** get_volume(uint8_t volume_idx){
    if ((volume_idx & 0x03u) == 0x01) {
        return rom_volume1;
    } else if ((volume_idx & 0x03u) == 0x03) {
        return rom_volume2;
    } else {
        return rom_volume0;
    }
}

void switch_volume(){
    uint8_t volume_idx = ram_io[0x0D];
    uint8_t** volume = get_volume(volume_idx);
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
    switch_bank();
}

static void generate_and_play_jg_wav(){

}

static uint8_t* get_zero_page_pointer(uint8_t index){
    if (index < 4) {
        return ram_io;
    } else {
        return ram_buff + ((index) << 6u);
    }
}

static uint8_t read_io_generic(uint8_t addr){
    return ram_io[addr];
}

static uint8_t read_io_3b_unknown(uint8_t addr){
    if (!(ram_io[0x3D] & 0x03u)) {
        return (uint8_t) (clock_buff[0x3Bu] & 0xFEu);
    }
    return ram_io[addr];
}

static uint8_t read_io_3f_clock(uint8_t addr){
    uint8_t idx = ram_io[0x3E];
    return (uint8_t) (idx < 80 ? clock_buff[idx] : 0);
}

static void write_io_generic(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
}

// switch bank.
static void write_io_00_bank_switch(uint8_t addr, uint8_t value){
    uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    if (value != old_value) {
        switch_bank();
    }
}

static void write_io_05_clock_ctrl(uint8_t addr, uint8_t value){
    uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    if ((old_value ^ value) & 0x08u) {
        nc1020_states -> slept = !(value & 0x08u);
    }
}

static void write_io_06_lcd_start_addr(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    if (!nc1020_states -> lcd_addr) {
        nc1020_states -> lcd_addr = ((ram_io[0x0C] & 0x03u) << 12u) | (value << 4u);
    }
    ram_io[0x09] &= 0xFEu;
}

static void write_io_08_port0(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    ram_io[0x0B] &= 0xFEu;
}

// keypad matrix.
static void write_io_09_port1(uint8_t addr, uint8_t value){
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
static void write_io_0a_roabbs(uint8_t addr, uint8_t value) {
    uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    if (value != old_value) {
        memmap[6] = bbs_pages[value & 0x0Fu];
    }
}

// switch volume
static void write_io_0d_volume_switch(uint8_t addr, uint8_t value){
    uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    if (value != old_value) {
        switch_volume();
    }
}

// zp40 switch
static void write_io_0f_zero_page_bank_switch(uint8_t addr, uint8_t value){
    uint8_t old_value = ram_io[addr];
    ram_io[addr] = value;
    old_value &= 0x07u;
    value &= 0x07u;
    if (value != old_value) {
        uint8_t* ptr_new = get_zero_page_pointer(value);
        if (old_value) {
            memcpy(get_zero_page_pointer(old_value), ram_40, 0x40);
            memcpy(ram_40, value ? ptr_new : bak_40, 0x40);
        } else {
            memcpy(bak_40, ram_40, 0x40);
            memcpy(ram_40, ptr_new, 0x40);
        }
    }
}

static void write_io_20_jg(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    if (value == 0x80 || value == 0x40) {
        memset(jg_wav_buff, 0, 0x20);
        ram_io[0x20] = 0;
        nc1020_states -> jg_wav_flags = 1;
        nc1020_states -> jg_wav_idx= 0;
    }
}

static void write_io_23_jg_wav(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    if (value == 0xC2) {
        jg_wav_buff[nc1020_states -> jg_wav_idx] = ram_io[0x22];
    } else if (value == 0xC4) {
        if (nc1020_states -> jg_wav_idx < 0x20) {
            jg_wav_buff[nc1020_states -> jg_wav_idx] = ram_io[0x22];
            nc1020_states -> jg_wav_idx ++;
        }
    } else if (value == 0x80) {
        ram_io[0x20] = 0x80;
        nc1020_states -> jg_wav_flags = 0;
        if (nc1020_states -> jg_wav_idx) {
            if (!nc1020_states -> jg_wav_playing) {
                generate_and_play_jg_wav();
                nc1020_states -> jg_wav_idx = 0;
            }
        }
    }
    if (nc1020_states -> jg_wav_playing) {
// todo.
    }
}

// clock.
static void write_io_3f_clock(uint8_t addr, uint8_t value){
    ram_io[addr] = value;
    uint8_t idx = ram_io[0x3E];
    if (idx >= 0x07) {
        if (idx == 0x0B) {
            ram_io[0x3D] = 0xF8;
            nc1020_states -> clock_flags |= value & 0x07u;
            clock_buff[0x0B] = (uint8_t) (value ^ ((clock_buff[0x0B] ^ value) & 0x7Fu));
        } else if (idx == 0x0A) {
            nc1020_states -> clock_flags |= value & 0x07u;
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

uint8_t read_io(uint8_t addr) {
    switch (addr) {
        case 0x3B:
            return read_io_3b_unknown(addr);
        case 0x3F:
            return read_io_3f_clock(addr);
        default:
            return read_io_generic(addr);
    }
}

uint8_t write_io(uint8_t addr, uint8_t value) {
    switch (addr) {
        case 0x00:
            write_io_00_bank_switch(addr, value);
            break;
        case 0x05:
            write_io_05_clock_ctrl(addr, value);
            break;
        case 0x06:
            write_io_06_lcd_start_addr(addr, value);
            break;
        case 0x08:
            write_io_08_port0(addr, value);
            break;
        case 0x09:
            write_io_09_port1(addr, value);
            break;
        case 0x0A:
            write_io_0a_roabbs(addr, value);
            break;
        case 0x0D:
            write_io_0d_volume_switch(addr, value);
            break;
        case 0x0F:
            write_io_0f_zero_page_bank_switch(addr, value);
            break;
        case 0x20:
            write_io_20_jg(addr, value);
            break;
        case 0x23:
            write_io_23_jg_wav(addr, value);
            break;
        case 0x3F:
            write_io_3f_clock(addr, value);
            break;
        default:
            write_io_generic(addr, value);
    }
}

