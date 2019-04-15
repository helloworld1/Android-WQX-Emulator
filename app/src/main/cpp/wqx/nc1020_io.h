//
// Created by liberty on 4/15/19.
//

#ifndef NC1020_NC1020_IO_H
#define NC1020_NC1020_IO_H
#include "nc1020_states.h"

void init_nc1020_io(nc1020_states_t *states, uint8_t rom_buff[], uint8_t nor_buff[], uint8_t* mmap[8]);
uint8_t read_io(uint8_t addr);
uint8_t write_io(uint8_t addr, uint8_t value);
void switch_volume();

#endif //NC1020_NC1020_IO_H
