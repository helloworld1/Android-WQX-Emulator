//
// Created by liberty on 4/12/19.
//
#include "cpu6502.h"
#include <stdint.h>
#include <stdbool.h>


static const uint16_t IRQ_VEC = 0xFFFE;

static uint8_t (*peek_byte)(uint16_t addr);

static uint8_t (*load)(uint16_t addr);

static void (*store)(uint16_t addr, uint8_t value);

static uint16_t peek_word(uint16_t addr) {
    return peek_byte(addr) | (peek_byte((uint16_t) (addr + 1u)) << 8u);
}

static void store_stack(uint8_t sp, uint8_t value) {
    uint16_t stack_ptr = (uint16_t) (0x100 + sp);
    store(stack_ptr, value);
}

static uint8_t load_stack(uint8_t sp) {
    return load((uint16_t) (0x100 + sp));
}

void init_6502(uint8_t (*Peek_func)(uint16_t addr),
        uint8_t (*Load_func)(uint16_t addr),
        void (*Store_func)(uint16_t addr, uint8_t value)) {
    peek_byte = Peek_func;
    load = Load_func;
    store = Store_func;
}

/**
 * @return cycles for the execution
 */
uint64_t do_irq(cpu_states_t *cpu_states) {
    uint8_t reg_sp = cpu_states -> reg_sp;
    uint16_t reg_pc = cpu_states -> reg_pc;
    uint8_t reg_ps = cpu_states -> reg_ps;

    if (!(reg_ps & 0x04u)) {
        store_stack(reg_sp--, (uint8_t) (reg_pc >> 8u));
        store_stack(reg_sp--, (uint8_t) (reg_pc & 0xFFu));
        reg_ps &= 0xEFu;
        store_stack(reg_sp--, reg_ps);
        reg_pc = peek_word(IRQ_VEC);
        reg_ps |= 0x04u;

        cpu_states -> reg_sp = reg_sp;
        cpu_states -> reg_pc = reg_pc;
        cpu_states -> reg_ps = reg_ps;
        return 7;
    }
    return 0;
}


/**
 * @return cycles for the execution
 */
uint64_t execute_6502(cpu_states_t *cpu_states) {
    uint64_t cycles = 0;
    uint16_t reg_pc = cpu_states -> reg_pc;
    uint8_t reg_a =  cpu_states -> reg_a;
    uint8_t reg_ps = cpu_states -> reg_ps;
    uint8_t reg_x = cpu_states -> reg_x;
    uint8_t reg_y = cpu_states -> reg_y;
    uint8_t reg_sp = cpu_states -> reg_sp;

    switch (peek_byte(reg_pc++)) {
        case 0x00: {
            reg_pc++;
            store_stack(reg_sp--, (uint8_t) (reg_pc >> 8u));
            store_stack(reg_sp--, (uint8_t) (reg_pc & 0xFFu));
            reg_ps |= 0x10u;
            store_stack(reg_sp--, reg_ps);
            reg_ps |= 0x04u;
            reg_pc = peek_word(IRQ_VEC);
            cycles += 7;
        }
            break;
        case 0x01: {
            uint16_t addr = peek_word((uint16_t) ((peek_byte(reg_pc++) + reg_x) & 0xFFu));
            reg_a |= load(addr);
            reg_ps &= 0x7Du;
            reg_ps |= (reg_a & 0x80u) | (!reg_a << 1u);
            cycles += 6;
        }
            break;
        case 0x02: {
        }
            break;
        case 0x03: {
        }
            break;
        case 0x04: {
        }
            break;
        case 0x05: {
            uint16_t addr = peek_byte(reg_pc++);
            reg_a |= load(addr);
            reg_ps &= 0x7Du;
            reg_ps |= (reg_a & 0x80u) | (!reg_a << 1u);
            cycles += 3;
        }
            break;
        case 0x06: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x7Cu;
            reg_ps |= (tmp1 >> 7u);
            tmp1 <<= 1u;
            reg_ps |= (tmp1 & 0x80u) | (!tmp1 << 1u);
            store(addr, tmp1);
            cycles += 5;
        }
            break;
        case 0x07: {
        }
            break;
        case 0x08: {
            store_stack(reg_sp--, reg_ps);
            cycles += 3;
        }
            break;
        case 0x09: {
            uint16_t addr = reg_pc++;
            reg_a |= load(addr);
            reg_ps &= 0x7Du;
            reg_ps |= (reg_a & 0x80u) | (!reg_a << 1u);
            cycles += 2;
        }
            break;
        case 0x0A: {
            reg_ps &= 0x7Cu;
            reg_ps |= reg_a >> 7u;
            reg_a <<= 1u;
            reg_ps |= (reg_a & 0x80u) | (!reg_a << 1u);
            cycles += 2;
        }
            break;
        case 0x0B: {
        }
            break;
        case 0x0C: {
        }
            break;
        case 0x0D: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            reg_a |= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x0E: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x7C;
            reg_ps |= (tmp1 >> 7);
            tmp1 <<= 1;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            store(addr, tmp1);
            cycles += 6;
        }
            break;
        case 0x0F: {
        }
            break;
        case 0x10: {
            int8_t tmp4 = (int8_t) (peek_byte(reg_pc++));
            uint16_t addr = reg_pc + tmp4;
            if (!(reg_ps & 0x80)) {
                cycles += !((reg_pc ^ addr) & 0xFF00) << 1;
                reg_pc = addr;
            }
            cycles += 2;
        }
            break;
        case 0x11: {
            uint16_t addr = peek_word(peek_byte(reg_pc));
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc++;
            reg_a |= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 5;
        }
            break;
        case 0x12: {
        }
            break;
        case 0x13: {
        }
            break;
        case 0x14: {
        }
            break;
        case 0x15: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            reg_a |= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x16: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x7C;
            reg_ps |= (tmp1 >> 7);
            tmp1 <<= 1;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            store(addr, tmp1);
            cycles += 6;
        }
            break;
        case 0x17: {
        }
            break;
        case 0x18: {
            reg_ps &= 0xFE;
            cycles += 2;
        }
            break;
        case 0x19: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc += 2;
            reg_a |= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x1A: {
        }
            break;
        case 0x1B: {
        }
            break;
        case 0x1C: {
        }
            break;
        case 0x1D: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_x) & 0xFF00);
            addr += reg_x;
            reg_pc += 2;
            reg_a |= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x1E: {
            uint16_t addr = peek_word(reg_pc);
            addr += reg_x;
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x7C;
            reg_ps |= (tmp1 >> 7);
            tmp1 <<= 1;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            store(addr, tmp1);
            cycles += 6;
        }
            break;
        case 0x1F: {
        }
            break;
        case 0x20: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            reg_pc--;
            store_stack(reg_sp--, (uint8_t) (reg_pc >> 8));
            store_stack(reg_sp--, (uint8_t) (reg_pc & 0xFF));
            reg_pc = addr;
            cycles += 6;
        }
            break;
        case 0x21: {
            uint16_t addr = peek_word((peek_byte(reg_pc++) + reg_x) & 0xFF);
            reg_a &= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 6;
        }
            break;
        case 0x22: {
        }
            break;
        case 0x23: {
        }
            break;
        case 0x24: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x3D;
            reg_ps |= (!(reg_a & tmp1) << 1) | (tmp1 & 0xC0);
            cycles += 3;
        }
            break;
        case 0x25: {
            uint16_t addr = peek_byte(reg_pc++);
            reg_a &= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 3;
        }
            break;
        case 0x26: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr);
            uint8_t tmp2 = (tmp1 << 1) | (reg_ps & 0x01);
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >> 7);
            store(addr, tmp2);
            cycles += 5;
        }
            break;
        case 0x27: {
        }
            break;
        case 0x28: {
            reg_ps = load_stack(++reg_sp);
            cycles += 4;
        }
            break;
        case 0x29: {
            uint16_t addr = reg_pc++;
            reg_a &= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 2;
        }
            break;
        case 0x2A: {
            uint8_t tmp1 = reg_a;
            reg_a = (reg_a << 1) | (reg_ps & 0x01);
            reg_ps &= 0x7C;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1) | (tmp1 >> 7);
            cycles += 2;
        }
            break;
        case 0x2B: {
        }
            break;
        case 0x2C: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x3D;
            reg_ps |= (!(reg_a & tmp1) << 1) | (tmp1 & 0xC0);
            cycles += 4;
        }
            break;
        case 0x2D: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            reg_a &= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x2E: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            uint8_t tmp2 = (tmp1 << 1) | (reg_ps & 0x01);
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >> 7);
            store(addr, tmp2);
            cycles += 6;
        }
            break;
        case 0x2F: {
        }
            break;
        case 0x30: {
            int8_t tmp4 = (int8_t) (peek_byte(reg_pc++));
            uint16_t addr = reg_pc + tmp4;
            if ((reg_ps & 0x80)) {
                cycles += !((reg_pc ^ addr) & 0xFF00) << 1;
                reg_pc = addr;
            }
            cycles += 2;
        }
            break;
        case 0x31: {
            uint16_t addr = peek_word(peek_byte(reg_pc));
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc++;
            reg_a &= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 5;
        }
            break;
        case 0x32: {
        }
            break;
        case 0x33: {
        }
            break;
        case 0x34: {
        }
            break;
        case 0x35: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            reg_a &= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x36: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            uint8_t tmp1 = load(addr);
            uint8_t tmp2 = (tmp1 << 1) | (reg_ps & 0x01);
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >> 7);
            store(addr, tmp2);
            cycles += 6;
        }
            break;
        case 0x37: {
        }
            break;
        case 0x38: {
            reg_ps |= 0x01;
            cycles += 2;
        }
            break;
        case 0x39: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc += 2;
            reg_a &= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x3A: {
        }
            break;
        case 0x3B: {
        }
            break;
        case 0x3C: {
        }
            break;
        case 0x3D: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_x) & 0xFF00);
            addr += reg_x;
            reg_pc += 2;
            reg_a &= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x3E: {
            uint16_t addr = peek_word(reg_pc);
            addr += reg_x;
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            uint8_t tmp2 = (tmp1 << 1) | (reg_ps & 0x01);
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >> 7);
            store(addr, tmp2);
            cycles += 6;
        }
            break;
        case 0x3F: {
        }
            break;
        case 0x40: {
            reg_ps = load_stack(++reg_sp);
            reg_pc = load_stack(++reg_sp);
            reg_pc |= (load_stack(++reg_sp) << 8);
            cycles += 6;
        }
            break;
        case 0x41: {
            uint16_t addr = peek_word((peek_byte(reg_pc++) + reg_x) & 0xFF);
            reg_a ^= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 6;
        }
            break;
        case 0x42: {
        }
            break;
        case 0x43: {
        }
            break;
        case 0x44: {
        }
            break;
        case 0x45: {
            uint16_t addr = peek_byte(reg_pc++);
            reg_a ^= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 3;
        }
            break;
        case 0x46: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x7C;
            reg_ps |= (tmp1 & 0x01);
            tmp1 >>= 1;
            reg_ps |= (!tmp1 << 1);
            store(addr, tmp1);
            cycles += 5;
        }
            break;
        case 0x47: {
        }
            break;
        case 0x48: {
            store_stack(reg_sp--, reg_a);
            cycles += 3;
        }
            break;
        case 0x49: {
            uint16_t addr = reg_pc++;
            reg_a ^= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 2;
        }
            break;
        case 0x4A: {
            reg_ps &= 0x7C;
            reg_ps |= reg_a & 0x01;
            reg_a >>= 1;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 2;
        }
            break;
        case 0x4B: {
        }
            break;
        case 0x4C: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            reg_pc = addr;
            cycles += 3;
        }
            break;
        case 0x4D: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            reg_a ^= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x4E: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x7C;
            reg_ps |= (tmp1 & 0x01);
            tmp1 >>= 1;
            reg_ps |= (!tmp1 << 1);
            store(addr, tmp1);
            cycles += 6;
        }
            break;
        case 0x4F: {
        }
            break;
        case 0x50: {
            int8_t tmp4 = (int8_t) (peek_byte(reg_pc++));
            uint16_t addr = reg_pc + tmp4;
            if (!(reg_ps & 0x40)) {
                cycles += !((reg_pc ^ addr) & 0xFF00) << 1;
                reg_pc = addr;
            }
            cycles += 2;
        }
            break;
        case 0x51: {
            uint16_t addr = peek_word(peek_byte(reg_pc));
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc++;
            reg_a ^= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 5;
        }
            break;
        case 0x52: {
        }
            break;
        case 0x53: {
        }
            break;
        case 0x54: {
        }
            break;
        case 0x55: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            reg_a ^= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x56: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x7C;
            reg_ps |= (tmp1 & 0x01);
            tmp1 >>= 1;
            reg_ps |= (!tmp1 << 1);
            store(addr, tmp1);
            cycles += 6;
        }
            break;
        case 0x57: {
        }
            break;
        case 0x58: {
            reg_ps &= 0xFB;
            cycles += 2;
        }
            break;
        case 0x59: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc += 2;
            reg_a ^= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x5A: {
        }
            break;
        case 0x5B: {
        }
            break;
        case 0x5C: {
        }
            break;
        case 0x5D: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_x) & 0xFF00);
            addr += reg_x;
            reg_pc += 2;
            reg_a ^= load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x5E: {
            uint16_t addr = peek_word(reg_pc);
            addr += reg_x;
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            reg_ps &= 0x7C;
            reg_ps |= (tmp1 & 0x01);
            tmp1 >>= 1;
            reg_ps |= (!tmp1 << 1);
            store(addr, tmp1);
            cycles += 6;
        }
            break;
        case 0x5F: {
        }
            break;
        case 0x60: {
            reg_pc = load_stack(++reg_sp);
            reg_pc |= (load_stack(++reg_sp) << 8);
            reg_pc++;
            cycles += 6;
        }
            break;
        case 0x61: {
            uint16_t addr = peek_word((peek_byte(reg_pc++) + reg_x) & 0xFF);
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a + tmp1 + (reg_ps & 0x01);
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 > 0xFF)
                      | (((reg_a ^ tmp1 ^ 0x80) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 6;
        }
            break;
        case 0x62: {
        }
            break;
        case 0x63: {
        }
            break;
        case 0x64: {
        }
            break;
        case 0x65: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a + tmp1 + (reg_ps & 0x01);
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 > 0xFF)
                      | (((reg_a ^ tmp1 ^ 0x80) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 3;
        }
            break;
        case 0x66: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr);
            uint8_t tmp2 = (tmp1 >> 1) | ((reg_ps & 0x01) << 7);
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 & 0x01);
            store(addr, tmp2);
            cycles += 5;
        }
            break;
        case 0x67: {
        }
            break;
        case 0x68: {
            reg_a = load_stack(++reg_sp);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0x69: {
            uint16_t addr = reg_pc++;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a + tmp1 + (reg_ps & 0x01);
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 > 0xFF)
                      | (((reg_a ^ tmp1 ^ 0x80) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 2;
        }
            break;
        case 0x6A: {
            uint8_t tmp1 = reg_a;
            reg_a = (reg_a >> 1) | ((reg_ps & 0x01) << 7);
            reg_ps &= 0x7C;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1) | (tmp1 & 0x01);
            cycles += 2;
        }
            break;
        case 0x6B: {
        }
            break;
        case 0x6C: {
            uint16_t addr = peek_word(peek_word(reg_pc));
            reg_pc += 2;
            reg_pc = addr;
            cycles += 6;
        }
            break;
        case 0x6D: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a + tmp1 + (reg_ps & 0x01);
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 > 0xFF)
                      | (((reg_a ^ tmp1 ^ 0x80) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 4;
        }
            break;
        case 0x6E: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            uint8_t tmp2 = (tmp1 >> 1) | ((reg_ps & 0x01) << 7);
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 & 0x01);
            store(addr, tmp2);
            cycles += 6;
        }
            break;
        case 0x6F: {
        }
            break;
        case 0x70: {
            int8_t tmp4 = (int8_t) (peek_byte(reg_pc++));
            uint16_t addr = reg_pc + tmp4;
            if ((reg_ps & 0x40)) {
                cycles += !((reg_pc ^ addr) & 0xFF00) << 1;
                reg_pc = addr;
            }
            cycles += 2;
        }
            break;
        case 0x71: {
            uint16_t addr = peek_word(peek_byte(reg_pc));
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc++;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a + tmp1 + (reg_ps & 0x01);
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 > 0xFF)
                      | (((reg_a ^ tmp1 ^ 0x80) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 5;
        }
            break;
        case 0x72: {
        }
            break;
        case 0x73: {
        }
            break;
        case 0x74: {
        }
            break;
        case 0x75: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a + tmp1 + (reg_ps & 0x01);
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 > 0xFF)
                      | (((reg_a ^ tmp1 ^ 0x80) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 4;
        }
            break;
        case 0x76: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            uint8_t tmp1 = load(addr);
            uint8_t tmp2 = (tmp1 >> 1) | ((reg_ps & 0x01) << 7);
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 & 0x01);
            store(addr, tmp2);
            cycles += 6;
        }
            break;
        case 0x77: {
        }
            break;
        case 0x78: {
            reg_ps |= 0x04;
            cycles += 2;
        }
            break;
        case 0x79: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a + tmp1 + (reg_ps & 0x01);
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 > 0xFF)
                      | (((reg_a ^ tmp1 ^ 0x80) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 4;
        }
            break;
        case 0x7A: {
        }
            break;
        case 0x7B: {
        }
            break;
        case 0x7C: {
        }
            break;
        case 0x7D: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_x) & 0xFF00);
            addr += reg_x;
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a + tmp1 + (reg_ps & 0x01);
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 > 0xFF)
                      | (((reg_a ^ tmp1 ^ 0x80) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 4;
        }
            break;
        case 0x7E: {
            uint16_t addr = peek_word(reg_pc);
            addr += reg_x;
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            uint8_t tmp2 = (tmp1 >> 1) | ((reg_ps & 0x01) << 7);
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 & 0x01);
            store(addr, tmp2);
            cycles += 6;
        }
            break;
        case 0x7F: {
        }
            break;
        case 0x80: {
        }
            break;
        case 0x81: {
            uint16_t addr = peek_word((peek_byte(reg_pc++) + reg_x) & 0xFF);
            store(addr, reg_a);
            cycles += 6;
        }
            break;
        case 0x82: {
        }
            break;
        case 0x83: {
        }
            break;
        case 0x84: {
            uint16_t addr = peek_byte(reg_pc++);
            store(addr, reg_y);
            cycles += 3;
        }
            break;
        case 0x85: {
            uint16_t addr = peek_byte(reg_pc++);
            store(addr, reg_a);
            cycles += 3;
        }
            break;
        case 0x86: {
            uint16_t addr = peek_byte(reg_pc++);
            store(addr, reg_x);
            cycles += 3;
        }
            break;
        case 0x87: {
        }
            break;
        case 0x88: {
            reg_y--;
            reg_ps &= 0x7D;
            reg_ps |= (reg_y & 0x80) | (!reg_y << 1);
            cycles += 2;
        }
            break;
        case 0x89: {
        }
            break;
        case 0x8A: {
            reg_a = reg_x;
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 2;
        }
            break;
        case 0x8B: {
        }
            break;
        case 0x8C: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            store(addr, reg_y);
            cycles += 4;
        }
            break;
        case 0x8D: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            store(addr, reg_a);
            cycles += 4;
        }
            break;
        case 0x8E: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            store(addr, reg_x);
            cycles += 4;
        }
            break;
        case 0x8F: {
        }
            break;
        case 0x90: {
            int8_t tmp4 = (int8_t) (peek_byte(reg_pc++));
            uint16_t addr = reg_pc + tmp4;
            if (!(reg_ps & 0x01)) {
                cycles += !((reg_pc ^ addr) & 0xFF00) << 1;
                reg_pc = addr;
            }
            cycles += 2;
        }
            break;
        case 0x91: {
            uint16_t addr = peek_word(peek_byte(reg_pc));
            addr += reg_y;
            reg_pc++;
            store(addr, reg_a);
            cycles += 6;
        }
            break;
        case 0x92: {
        }
            break;
        case 0x93: {
        }
            break;
        case 0x94: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            store(addr, reg_y);
            cycles += 4;
        }
            break;
        case 0x95: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            store(addr, reg_a);
            cycles += 4;
        }
            break;
        case 0x96: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_y) & 0xFF;
            store(addr, reg_x);
            cycles += 4;
        }
            break;
        case 0x97: {
        }
            break;
        case 0x98: {
            reg_a = reg_y;
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 2;
        }
            break;
        case 0x99: {
            uint16_t addr = peek_word(reg_pc);
            addr += reg_y;
            reg_pc += 2;
            store(addr, reg_a);
            cycles += 5;
        }
            break;
        case 0x9A: {
            reg_sp = reg_x;
            cycles += 2;
        }
            break;
        case 0x9B: {
        }
            break;
        case 0x9C: {
        }
            break;
        case 0x9D: {
            uint16_t addr = peek_word(reg_pc);
            addr += reg_x;
            reg_pc += 2;
            store(addr, reg_a);
            cycles += 5;
        }
            break;
        case 0x9E: {
        }
            break;
        case 0x9F: {
        }
            break;
        case 0xA0: {
            uint16_t addr = reg_pc++;
            reg_y = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_y & 0x80) | (!reg_y << 1);
            cycles += 2;
        }
            break;
        case 0xA1: {
            uint16_t addr = peek_word((peek_byte(reg_pc++) + reg_x) & 0xFF);
            reg_a = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 6;
        }
            break;
        case 0xA2: {
            uint16_t addr = reg_pc++;
            reg_x = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_x & 0x80) | (!reg_x << 1);
            cycles += 2;
        }
            break;
        case 0xA3: {
        }
            break;
        case 0xA4: {
            uint16_t addr = peek_byte(reg_pc++);
            reg_y = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_y & 0x80) | (!reg_y << 1);
            cycles += 3;
        }
            break;
        case 0xA5: {
            uint16_t addr = peek_byte(reg_pc++);
            reg_a = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 3;
        }
            break;
        case 0xA6: {
            uint16_t addr = peek_byte(reg_pc++);
            reg_x = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_x & 0x80) | (!reg_x << 1);
            cycles += 3;
        }
            break;
        case 0xA7: {
        }
            break;
        case 0xA8: {
            reg_y = reg_a;
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 2;
        }
            break;
        case 0xA9: {
            uint16_t addr = reg_pc++;
            reg_a = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 2;
        }
            break;
        case 0xAA: {
            reg_x = reg_a;
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 2;
        }
            break;
        case 0xAB: {
        }
            break;
        case 0xAC: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            reg_y = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_y & 0x80) | (!reg_y << 1);
            cycles += 4;
        }
            break;
        case 0xAD: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            reg_a = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0xAE: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            reg_x = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_x & 0x80) | (!reg_x << 1);
            cycles += 4;
        }
            break;
        case 0xAF: {
        }
            break;
        case 0xB0: {
            int8_t tmp4 = (int8_t) (peek_byte(reg_pc++));
            uint16_t addr = reg_pc + tmp4;
            if ((reg_ps & 0x01)) {
                cycles += !((reg_pc ^ addr) & 0xFF00) << 1;
                reg_pc = addr;
            }
            cycles += 2;
        }
            break;
        case 0xB1: {
            uint16_t addr = peek_word(peek_byte(reg_pc));
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc++;
            reg_a = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 5;
        }
            break;
        case 0xB2: {
        }
            break;
        case 0xB3: {
        }
            break;
        case 0xB4: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            reg_y = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_y & 0x80) | (!reg_y << 1);
            cycles += 4;
        }
            break;
        case 0xB5: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            reg_a = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0xB6: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_y) & 0xFF;
            reg_x = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_x & 0x80) | (!reg_x << 1);
            cycles += 4;
        }
            break;
        case 0xB7: {
        }
            break;
        case 0xB8: {
            reg_ps &= 0xBF;
            cycles += 2;
        }
            break;
        case 0xB9: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc += 2;
            reg_a = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0xBA: {
            reg_x = reg_sp;
            reg_ps &= 0x7D;
            reg_ps |= (reg_x & 0x80) | (!reg_x << 1);
            cycles += 2;
        }
            break;
        case 0xBB: {
        }
            break;
        case 0xBC: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_x) & 0xFF00);
            addr += reg_x;
            reg_pc += 2;
            reg_y = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_y & 0x80) | (!reg_y << 1);
            cycles += 4;
        }
            break;
        case 0xBD: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_x) & 0xFF00);
            addr += reg_x;
            reg_pc += 2;
            reg_a = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_a & 0x80) | (!reg_a << 1);
            cycles += 4;
        }
            break;
        case 0xBE: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc += 2;
            reg_x = load(addr);
            reg_ps &= 0x7D;
            reg_ps |= (reg_x & 0x80) | (!reg_x << 1);
            cycles += 4;
        }
            break;
        case 0xBF: {
        }
            break;
        case 0xC0: {
            uint16_t addr = reg_pc++;
            int16_t tmp1 = reg_y - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 2;
        }
            break;
        case 0xC1: {
            uint16_t addr = peek_word((peek_byte(reg_pc++) + reg_x) & 0xFF);
            int16_t tmp1 = reg_a - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 6;
        }
            break;
        case 0xC2: {
        }
            break;
        case 0xC3: {
        }
            break;
        case 0xC4: {
            uint16_t addr = peek_byte(reg_pc++);
            int16_t tmp1 = reg_y - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 3;
        }
            break;
        case 0xC5: {
            uint16_t addr = peek_byte(reg_pc++);
            int16_t tmp1 = reg_a - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 3;
        }
            break;
        case 0xC6: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr) - 1;
            store(addr, tmp1);
            reg_ps &= 0x7D;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            cycles += 5;
        }
            break;
        case 0xC7: {
        }
            break;
        case 0xC8: {
            reg_y++;
            reg_ps &= 0x7D;
            reg_ps |= (reg_y & 0x80) | (!reg_y << 1);
            cycles += 2;
        }
            break;
        case 0xC9: {
            uint16_t addr = reg_pc++;
            int16_t tmp1 = reg_a - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 2;
        }
            break;
        case 0xCA: {
            reg_x--;
            reg_ps &= 0x7D;
            reg_ps |= (reg_x & 0x80) | (!reg_x << 1);
            cycles += 2;
        }
            break;
        case 0xCB: {
        }
            break;
        case 0xCC: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            int16_t tmp1 = reg_y - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 4;
        }
            break;
        case 0xCD: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            int16_t tmp1 = reg_a - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 4;
        }
            break;
        case 0xCE: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr) - 1;
            store(addr, tmp1);
            reg_ps &= 0x7D;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            cycles += 6;
        }
            break;
        case 0xCF: {
        }
            break;
        case 0xD0: {
            int8_t tmp4 = (int8_t) (peek_byte(reg_pc++));
            uint16_t addr = reg_pc + tmp4;
            if (!(reg_ps & 0x02)) {
                cycles += !((reg_pc ^ addr) & 0xFF00) << 1;
                reg_pc = addr;
            }
            cycles += 2;
        }
            break;
        case 0xD1: {
            uint16_t addr = peek_word(peek_byte(reg_pc));
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc++;
            int16_t tmp1 = reg_a - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 5;
        }
            break;
        case 0xD2: {
        }
            break;
        case 0xD3: {
        }
            break;
        case 0xD4: {
        }
            break;
        case 0xD5: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            int16_t tmp1 = reg_a - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 4;
        }
            break;
        case 0xD6: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            uint8_t tmp1 = load(addr) - 1;
            store(addr, tmp1);
            reg_ps &= 0x7D;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            cycles += 6;
        }
            break;
        case 0xD7: {
        }
            break;
        case 0xD8: {
            reg_ps &= 0xF7;
            cycles += 2;
        }
            break;
        case 0xD9: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc += 2;
            int16_t tmp1 = reg_a - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 4;
        }
            break;
        case 0xDA: {
        }
            break;
        case 0xDB: {
        }
            break;
        case 0xDC: {
        }
            break;
        case 0xDD: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_x) & 0xFF00);
            addr += reg_x;
            reg_pc += 2;
            int16_t tmp1 = reg_a - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 4;
        }
            break;
        case 0xDE: {
            uint16_t addr = peek_word(reg_pc);
            addr += reg_x;
            reg_pc += 2;
            uint8_t tmp1 = load(addr) - 1;
            store(addr, tmp1);
            reg_ps &= 0x7D;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            cycles += 6;
        }
            break;
        case 0xDF: {
        }
            break;
        case 0xE0: {
            uint16_t addr = reg_pc++;
            int16_t tmp1 = reg_x - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 2;
        }
            break;
        case 0xE1: {
            uint16_t addr = peek_word((peek_byte(reg_pc++) + reg_x) & 0xFF);
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a - tmp1 + (reg_ps & 0x01u) - 1u;
            uint8_t tmp3 = tmp2 & 0xFFu;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 >= 0)
                      | (((reg_a ^ tmp1) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 6;
        }
            break;
        case 0xE2: {
        }
            break;
        case 0xE3: {
        }
            break;
        case 0xE4: {
            uint16_t addr = peek_byte(reg_pc++);
            int16_t tmp1 = reg_x - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 3;
        }
            break;
        case 0xE5: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a - tmp1 + (reg_ps & 0x01) - 1;
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 >= 0)
                      | (((reg_a ^ tmp1) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 3;
        }
            break;
        case 0xE6: {
            uint16_t addr = peek_byte(reg_pc++);
            uint8_t tmp1 = load(addr) + 1;
            store(addr, tmp1);
            reg_ps &= 0x7D;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            cycles += 5;
        }
            break;
        case 0xE7: {
        }
            break;
        case 0xE8: {
            reg_x++;
            reg_ps &= 0x7D;
            reg_ps |= (reg_x & 0x80) | (!reg_x << 1);
            cycles += 2;
        }
            break;
        case 0xE9: {
            uint16_t addr = reg_pc++;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a - tmp1 + (reg_ps & 0x01) - 1;
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 >= 0)
                      | (((reg_a ^ tmp1) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 2;
        }
            break;
        case 0xEA: {
            cycles += 2;
        }
            break;
        case 0xEB: {
        }
            break;
        case 0xEC: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            int16_t tmp1 = reg_x - load(addr);
            uint8_t tmp2 = tmp1 & 0xFF;
            reg_ps &= 0x7C;
            reg_ps |= (tmp2 & 0x80) | (!tmp2 << 1) | (tmp1 >= 0);
            cycles += 4;
        }
            break;
        case 0xED: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a - tmp1 + (reg_ps & 0x01) - 1;
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 >= 0)
                      | (((reg_a ^ tmp1) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 4;
        }
            break;
        case 0xEE: {
            uint16_t addr = peek_word(reg_pc);
            reg_pc += 2;
            uint8_t tmp1 = load(addr) + 1;
            store(addr, tmp1);
            reg_ps &= 0x7D;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            cycles += 6;
        }
            break;
        case 0xEF: {
        }
            break;
        case 0xF0: {
            int8_t tmp4 = (int8_t) (peek_byte(reg_pc++));
            uint16_t addr = reg_pc + tmp4;
            if ((reg_ps & 0x02)) {
                cycles += !((reg_pc ^ addr) & 0xFF00) << 1;
                reg_pc = addr;
            }
            cycles += 2;
        }
            break;
        case 0xF1: {
            uint16_t addr = peek_word(peek_byte(reg_pc));
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc++;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a - tmp1 + (reg_ps & 0x01) - 1;
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 >= 0)
                      | (((reg_a ^ tmp1) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 5;
        }
            break;
        case 0xF2: {
        }
            break;
        case 0xF3: {
        }
            break;
        case 0xF4: {
        }
            break;
        case 0xF5: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a - tmp1 + (reg_ps & 0x01) - 1;
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 >= 0)
                      | (((reg_a ^ tmp1) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 4;
        }
            break;
        case 0xF6: {
            uint16_t addr = (peek_byte(reg_pc++) + reg_x) & 0xFF;
            uint8_t tmp1 = load(addr) + 1;
            store(addr, tmp1);
            reg_ps &= 0x7D;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            cycles += 6;
        }
            break;
        case 0xF7: {
        }
            break;
        case 0xF8: {
            reg_ps |= 0x08;
            cycles += 2;
        }
            break;
        case 0xF9: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_y) & 0xFF00);
            addr += reg_y;
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a - tmp1 + (reg_ps & 0x01) - 1;
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 >= 0)
                      | (((reg_a ^ tmp1) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 4;
        }
            break;
        case 0xFA: {
        }
            break;
        case 0xFB: {
        }
            break;
        case 0xFC: {
        }
            break;
        case 0xFD: {
            uint16_t addr = peek_word(reg_pc);
            cycles += !!(((addr & 0xFF) + reg_x) & 0xFF00);
            addr += reg_x;
            reg_pc += 2;
            uint8_t tmp1 = load(addr);
            int16_t tmp2 = reg_a - tmp1 + (reg_ps & 0x01) - 1;
            uint8_t tmp3 = tmp2 & 0xFF;
            reg_ps &= 0x3C;
            reg_ps |= (tmp3 & 0x80) | (!tmp3 << 1) | (tmp2 >= 0)
                      | (((reg_a ^ tmp1) & (reg_a ^ tmp3) & 0x80) >> 1);
            reg_a = tmp3;
            cycles += 4;
        }
            break;
        case 0xFE: {
            uint16_t addr = peek_word(reg_pc);
            addr += reg_x;
            reg_pc += 2;
            uint8_t tmp1 = load(addr) + 1;
            store(addr, tmp1);
            reg_ps &= 0x7D;
            reg_ps |= (tmp1 & 0x80) | (!tmp1 << 1);
            cycles += 6;
        }
            break;
        case 0xFF: {
        }
            break;
    }

    cpu_states -> reg_pc = reg_pc;
    cpu_states -> reg_a = reg_a;
    cpu_states -> reg_ps = reg_ps;
    cpu_states -> reg_x = reg_x;
    cpu_states -> reg_y = reg_y;
    cpu_states -> reg_sp = reg_sp;


    return cycles;
}
