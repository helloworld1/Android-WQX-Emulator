#ifndef NC1020_ANDROID_AUDIO_H_
#define NC1020_ANDROID_AUDIO_H_

#include <stdbool.h>

void android_audio_start(void);
void android_audio_stop(void);
void android_audio_set_enabled(bool enabled);

#endif /* NC1020_ANDROID_AUDIO_H_ */
