#ifndef NC1020_DSP_H_
#define NC1020_DSP_H_

#include <stdbool.h>
#include <stdint.h>

void dsp_init(void);
void dsp_reset(void);
void dsp_post_load_states(bool sleeping);
bool dsp_is_sleeping(void);

uint8_t dsp_read_stat(uint64_t current_cycle);
uint8_t dsp_read_ret_data(void);
void dsp_write_ctrl(uint8_t value);
void dsp_write_low(uint8_t value);
void dsp_write_cmd_high(uint8_t value, uint64_t current_cycle);

#endif /* NC1020_DSP_H_ */
