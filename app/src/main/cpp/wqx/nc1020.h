#ifndef NC1020_H_
#define NC1020_H_

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

extern void initialize(const char *);
extern void reset();
extern void set_key(uint8_t, bool);
extern void run_time_slice(uint64_t, bool);
extern bool copy_lcd_buffer(uint8_t *);
extern void load_nc1020();
extern void save_nc1020();
extern uint64_t get_cycles();
extern void delete_state_and_nor();

#endif /* NC1020_H_ */
