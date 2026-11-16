#ifndef NC1020_FS_H_
#define NC1020_FS_H_

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define NC1020_FS_NOR_SIZE        (1024u * 1024u)
#define NC1020_FS_BLOCK_SIZE      4096u
#define NC1020_FS_TOTAL_BLOCKS    256u
#define NC1020_FS_MAX_NAME_BYTES  16u
#define NC1020_FS_MAX_DIR_ENTRIES 127u
#define NC1020_FS_ROOT_INODE      0u

typedef enum {
    NC1020_FS_OK               = 0,
    NC1020_FS_ERR_CORRUPT      = -1,
    NC1020_FS_ERR_NOT_FOUND    = -2,
    NC1020_FS_ERR_EXISTS       = -3,
    NC1020_FS_ERR_NO_SPACE     = -4,
    NC1020_FS_ERR_NAME_INVALID = -5,
    NC1020_FS_ERR_NOT_EMPTY    = -6,
    NC1020_FS_ERR_IS_DIR       = -7,
    NC1020_FS_ERR_NOT_DIR      = -8,
    NC1020_FS_ERR_DIR_FULL     = -9
} nc1020_fs_status_t;

typedef struct {
    uint16_t bat_block;
    uint16_t root_block;
    uint16_t total_blocks;
    uint16_t reserved_blocks;
    uint16_t used_blocks;
    uint16_t free_blocks;
    uint16_t dirty_blocks;
    uint16_t wear_count;
    bool has_open_fcb;
} nc1020_fs_info_t;

typedef struct {
    uint8_t name_gbk[NC1020_FS_MAX_NAME_BYTES];
    uint8_t name_len;
    uint8_t attr;
    uint8_t attr2;
    uint16_t year;
    uint8_t month;
    uint8_t day;
    uint32_t size;
    uint16_t inode;
    uint16_t blocks;
} nc1020_fs_entry_t;

int nc1020_fs_get_info(nc1020_fs_info_t *out_info);

int nc1020_fs_list_dir(
    uint16_t dir_inode,
    nc1020_fs_entry_t *out_entries,
    int max_entries,
    int *out_count
);

int nc1020_fs_read_file(
    uint16_t inode,
    uint8_t *out_buf,
    uint32_t buf_capacity,
    uint32_t expected_size,
    uint32_t *out_bytes_read
);

int nc1020_fs_mkdir(
    uint16_t parent_inode,
    const uint8_t *name_gbk,
    size_t name_len,
    bool save_states,
    uint16_t *out_inode
);

int nc1020_fs_write_file(
    uint16_t dir_inode,
    const uint8_t *name_gbk,
    size_t name_len,
    const uint8_t *data,
    uint32_t size,
    bool overwrite,
    bool save_states,
    uint16_t *out_inode
);

int nc1020_fs_delete(
    uint16_t dir_inode,
    uint16_t target_inode,
    bool save_states
);

int nc1020_fs_rename(
    uint16_t dir_inode,
    uint16_t target_inode,
    const uint8_t *new_name_gbk,
    size_t new_name_len,
    bool save_states
);

#endif /* NC1020_FS_H_ */
