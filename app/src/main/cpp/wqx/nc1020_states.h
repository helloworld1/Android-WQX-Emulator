//
// Created by liberty on 4/15/19.
//
#include <stdint.h>
#include <stdbool.h>
#include "cpu6502.h"

#ifndef NC1020_NC1020_STATES_H
#define NC1020_NC1020_STATES_H

typedef struct {
    uint64_t version;
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
    uint64_t cycles;
    uint64_t timer0_cycles;
    uint64_t timer1_cycles;
    bool should_irq;

    uint64_t lcd_addr;
    uint8_t keypad_matrix[8];
} nc1020_states_t;


#endif //NC1020_NC1020_STATES_H
