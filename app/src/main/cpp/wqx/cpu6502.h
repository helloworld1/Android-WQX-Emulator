//
// Created by liberty on 4/12/19.
//

#ifndef NC1020_CPU6502_H
#define NC1020_CPU6502_H

#include <stdint.h>

typedef struct {
    uint16_t reg_pc;
    uint8_t reg_a;
    uint8_t reg_ps;
    uint8_t reg_x;
    uint8_t reg_y;
    uint8_t reg_sp;
} cpu_states_t;

void init_6502(uint8_t (*Peek_func)(uint16_t addr),
               uint8_t (*Load_func)(uint16_t addr),
               void (*Store_func)(uint16_t addr, uint8_t value));

int execute_6502(cpu_states_t *cpu_states);

int do_irq(cpu_states_t *cpu_states);

#endif //NC1020_CPU6502_H
