#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <time.h>
#include "nc1020_states.h"

static nc1020_states_t *_nc1020_states;

static uint8_t *_rom_volume0[0x100];
static uint8_t *_rom_volume1[0x100];
static uint8_t *_rom_volume2[0x100];

static uint8_t *_nor_banks[0x20];
static uint8_t *_bbs_pages[0x10];

static uint8_t **_memmap;

static uint8_t *_ram_buff;
static uint8_t *_ram_io;
static uint8_t *_ram_40;
static uint8_t *_ram_page1;
static uint8_t *_ram_page2;
static uint8_t *_ram_page3;

static uint8_t *_clock_buff;
static uint8_t *_jg_wav_buff;
static uint8_t *_bak_40;
static uint8_t *_keypad_matrix;

static uint8_t* get_bank(uint8_t bank_idx){
    uint8_t volume_idx = _ram_io[0x0D];
    if (bank_idx < 0x20) {
        return _nor_banks[bank_idx];
    } else if (bank_idx >= 0x80) {
        if (volume_idx & 0x01u) {
            return _rom_volume1[bank_idx];
        } else if (volume_idx & 0x02u) {
            return _rom_volume2[bank_idx];
        } else {
            return _rom_volume0[bank_idx];
        }
    }
    return NULL;
}

static void switch_bank(){
    uint8_t bank_idx = _ram_io[0x00];
    uint8_t* bank = get_bank(bank_idx);
    _memmap[2] = bank;
    _memmap[3] = bank + 0x2000;
    _memmap[4] = bank + 0x4000;
    _memmap[5] = bank + 0x6000;
}

static uint8_t** get_volume(uint8_t volume_idx){
    if ((volume_idx & 0x03u) == 0x01) {
        return _rom_volume1;
    } else if ((volume_idx & 0x03u) == 0x03) {
        return _rom_volume2;
    } else {
        return _rom_volume0;
    }
}

void switch_volume(){
    uint8_t volume_idx = _ram_io[0x0D];
    uint8_t** volume = get_volume(volume_idx);
    for (int i=0; i<4; i++) {
        _bbs_pages[i * 4] = volume[i];
        _bbs_pages[i * 4 + 1] = volume[i] + 0x2000;
        _bbs_pages[i * 4 + 2] = volume[i] + 0x4000;
        _bbs_pages[i * 4 + 3] = volume[i] + 0x6000;
    }
    _bbs_pages[1] = _ram_page3;
    _memmap[7] = volume[0] + 0x2000;
    uint8_t roa_bbs = _ram_io[0x0A];
    _memmap[1] = (roa_bbs & 0x04u ? _ram_page2 : _ram_page1);
    _memmap[6] = _bbs_pages[roa_bbs & 0x0Fu];
    switch_bank();
}

static void generate_and_play_jg_wav(){

}

static uint8_t* get_zero_page_pointer(uint8_t index){
    if (index < 4) {
        return _ram_io;
    } else {
        return _ram_buff + ((index) << 6u);
    }
}

static uint8_t read_io_generic(uint8_t addr){
    return _ram_io[addr];
}

static uint8_t read_io_3b_unknown(uint8_t addr){
    if (!(_ram_io[0x3D] & 0x03u)) {
        return (uint8_t) (_clock_buff[0x3Bu] & 0xFEu);
    }
    return _ram_io[addr];
}

static uint8_t read_io_3f_clock(uint8_t addr){
    uint8_t idx = _ram_io[0x3E];
    return (uint8_t) (idx < 80 ? _clock_buff[idx] : 0);
}

static void write_io_generic(uint8_t addr, uint8_t value){
    _ram_io[addr] = value;
}

// switch bank.
static void write_io_00_bank_switch(uint8_t addr, uint8_t value){
    uint8_t old_value = _ram_io[addr];
    _ram_io[addr] = value;
    if (value != old_value) {
        switch_bank();
    }
}

static void write_io_05_clock_ctrl(uint8_t addr, uint8_t value){
    uint8_t old_value = _ram_io[addr];
    _ram_io[addr] = value;
    if ((old_value ^ value) & 0x08u) {
        _nc1020_states -> slept = !(value & 0x08u);
    }
}

static void write_io_06_lcd_start_addr(uint8_t addr, uint8_t value){
    _ram_io[addr] = value;
    if (!_nc1020_states -> lcd_addr) {
        _nc1020_states -> lcd_addr = ((_ram_io[0x0C] & 0x03u) << 12u) | (value << 4u);
    }
    _ram_io[0x09] &= 0xFEu;
}

static void write_io_08_port0(uint8_t addr, uint8_t value){
    _ram_io[addr] = value;
    _ram_io[0x0B] &= 0xFEu;
}

// keypad matrix.
static void write_io_09_port1(uint8_t addr, uint8_t value){
    _ram_io[addr] = value;
    switch (value){
        case 0x01: _ram_io[0x08] = _keypad_matrix[0]; break;
        case 0x02: _ram_io[0x08] = _keypad_matrix[1]; break;
        case 0x04: _ram_io[0x08] = _keypad_matrix[2]; break;
        case 0x08: _ram_io[0x08] = _keypad_matrix[3]; break;
        case 0x10: _ram_io[0x08] = _keypad_matrix[4]; break;
        case 0x20: _ram_io[0x08] = _keypad_matrix[5]; break;
        case 0x40: _ram_io[0x08] = _keypad_matrix[6]; break;
        case 0x80: _ram_io[0x08] = _keypad_matrix[7]; break;
        case 0:
            _ram_io[0x0B] |= 1u;
            if (_keypad_matrix[7] == 0xFE) {
                _ram_io[0x0B] &= 0xFEu;
            }
            break;
        case 0x7F:
            if (_ram_io[0x15] == 0x7Fu) {
                _ram_io[0x08] = _keypad_matrix[0] |
                               _keypad_matrix[1] |
                               _keypad_matrix[2] |
                               _keypad_matrix[3] |
                               _keypad_matrix[4] |
                               _keypad_matrix[5] |
                               _keypad_matrix[6] |
                               _keypad_matrix[7];
            }
            break;
        default:break;
    }
}

// roabbs
static void write_io_0a_roabbs(uint8_t addr, uint8_t value) {
    uint8_t old_value = _ram_io[addr];
    _ram_io[addr] = value;
    if (value != old_value) {
        _memmap[6] = _bbs_pages[value & 0x0Fu];
    }
}

// switch volume
static void write_io_0d_volume_switch(uint8_t addr, uint8_t value){
    uint8_t old_value = _ram_io[addr];
    _ram_io[addr] = value;
    if (value != old_value) {
        switch_volume();
    }
}

// zp40 switch
static void write_io_0f_zero_page_bank_switch(uint8_t addr, uint8_t value){
    uint8_t old_value = _ram_io[addr];
    _ram_io[addr] = value;
    old_value &= 0x07u;
    value &= 0x07u;
    if (value != old_value) {
        uint8_t* ptr_new = get_zero_page_pointer(value);
        if (old_value) {
            memcpy(get_zero_page_pointer(old_value), _ram_40, 0x40);
            memcpy(_ram_40, value ? ptr_new : _bak_40, 0x40);
        } else {
            memcpy(_bak_40, _ram_40, 0x40);
            memcpy(_ram_40, ptr_new, 0x40);
        }
    }
}

static void write_io_20_jg(uint8_t addr, uint8_t value){
    _ram_io[addr] = value;
    if (value == 0x80 || value == 0x40) {
        memset(_jg_wav_buff, 0, 0x20);
        _ram_io[0x20] = 0;
        _nc1020_states -> jg_wav_flags = 1;
        _nc1020_states -> jg_wav_idx= 0;
    }
}

static void write_io_23_jg_wav(uint8_t addr, uint8_t value){
    _ram_io[addr] = value;
    if (value == 0xC2) {
        _jg_wav_buff[_nc1020_states -> jg_wav_idx] = _ram_io[0x22];
    } else if (value == 0xC4) {
        if (_nc1020_states -> jg_wav_idx < 0x20) {
            _jg_wav_buff[_nc1020_states -> jg_wav_idx] = _ram_io[0x22];
            _nc1020_states -> jg_wav_idx ++;
        }
    } else if (value == 0x80) {
        _ram_io[0x20] = 0x80;
        _nc1020_states -> jg_wav_flags = 0;
        if (_nc1020_states -> jg_wav_idx) {
            if (!_nc1020_states -> jg_wav_playing) {
                generate_and_play_jg_wav();
                _nc1020_states -> jg_wav_idx = 0;
            }
        }
    }
    if (_nc1020_states -> jg_wav_playing) {
// todo.
    }
}

static void write_io_3f_clock(uint8_t addr, uint8_t value){
    _ram_io[addr] = value;
    uint8_t idx = _ram_io[0x3E];
    if (idx >= 0x07) {
        if (idx == 0x0B) {
            _ram_io[0x3D] = 0xF8;
            _nc1020_states -> clock_flags |= value & 0x07u;
            _clock_buff[0x0B] = (uint8_t) (value ^ ((_clock_buff[0x0B] ^ value) & 0x7Fu));
        } else if (idx == 0x0A) {
            _nc1020_states -> clock_flags |= value & 0x07u;
            _clock_buff[0x0A] = value;
        } else {
            _clock_buff[idx % 80] = value;
        }
    } else {
        if (!(_clock_buff[0x0B] & 0x80u) && idx < 80u) {
            _clock_buff[idx] = value;
        }
    }
}

void init_nc1020_io(nc1020_states_t *states, uint8_t rom_buff[], uint8_t nor_buff[], uint8_t* mmap[8]) {
    _nc1020_states = states;

    _ram_buff = _nc1020_states -> ram;
    _ram_io = _ram_buff;
    _ram_40 = _ram_buff + 0x40;
    _ram_page1 = _ram_buff + 0x2000;
    _ram_page2 = _ram_buff + 0x4000;
    _ram_page3 = _ram_buff + 0x6000;
    _clock_buff = _nc1020_states -> clock_data;
    _jg_wav_buff = _nc1020_states -> jg_wav_data;
    _bak_40 = _nc1020_states -> bak_40;
    _keypad_matrix = _nc1020_states -> keypad_matrix;
    _memmap = mmap;

    for (uint64_t i=0; i<0x100; i++) {
        _rom_volume0[i] = rom_buff + (0x8000 * i);
        _rom_volume1[i] = rom_buff + (0x8000 * (0x100 + i));
        _rom_volume2[i] = rom_buff + (0x8000 * (0x200 + i));
    }
    for (uint64_t i=0; i<0x20; i++) {
        _nor_banks[i] = nor_buff + (0x8000 * i);
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
