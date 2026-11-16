#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif

#include "nc1020_fs.h"
#include "nc1020.h"

#include <string.h>
#include <time.h>

#define BAT_OFFSET_ENTRIES 0x010u
#define BAT_OFFSET_WEAR    0xFFEu
#define DIR_SLOT_BYTES     32u
#define SLOTS_PER_BLOCK    128u
#define MAX_INODE_ID       511u

typedef enum {
    BLK_FREE = 0,
    BLK_RESERVED,
    BLK_BAT,
    BLK_ACTIVE_CHUNK,
    BLK_DIRTY
} blk_kind_t;

static bool is_system_reserved_block(uint16_t blk, const uint8_t ent[4]) {
    if (blk < 0x20u || (blk >= 0x7Cu && blk <= 0x7Fu)) {
        return true;
    }
    return ent[0] == 0xFFu && ent[1] == 0x5Fu && ent[2] == 0xFFu && (ent[3] & 0xFEu) == 0x00u;
}

static int find_bat_block(const uint8_t *nor) {
    int fallback = -1;
    for (int blk = 0; blk < (int) NC1020_FS_TOTAL_BLOCKS; blk++) {
        const uint8_t *p = nor + ((size_t) blk * NC1020_FS_BLOCK_SIZE);
        if (memcmp(p, "WQXFlashSX", 10u) == 0) {
            if (p[0x0Au] == 0xAAu) {
                return blk;
            }
            if (fallback < 0) {
                fallback = blk;
            }
        }
    }
    return fallback;
}

static blk_kind_t classify_bat_entry(
    uint16_t blk,
    uint16_t bat_blk,
    const uint8_t ent[4],
    uint16_t *out_inode,
    uint8_t *out_chunk_idx
) {
    if (blk == bat_blk ||
        (ent[0] == 0xFFu && ent[1] == 0x5Fu && ent[2] == 0xFFu && ent[3] == 0x03u)) {
        return BLK_BAT;
    }
    if (is_system_reserved_block(blk, ent)) {
        return BLK_RESERVED;
    }
    if (ent[0] == 0xFFu && ent[1] == 0xFFu && ent[2] == 0xFFu && ent[3] == 0xFFu) {
        return BLK_FREE;
    }
    if ((ent[1] & 0xE0u) == 0x40u) {
        if (out_inode != NULL) {
            *out_inode = (uint16_t) ent[0] | ((uint16_t) (ent[1] & 0x01u) << 8u);
        }
        if (out_chunk_idx != NULL) {
            *out_chunk_idx = ent[2];
        }
        return BLK_ACTIVE_CHUNK;
    }
    return BLK_DIRTY;
}

static int collect_inode_chunks(
    const uint8_t *bat_ptr,
    uint16_t bat_blk,
    uint16_t target_inode,
    uint16_t out_blocks[NC1020_FS_TOTAL_BLOCKS],
    int *out_count
) {
    for (size_t i = 0; i < NC1020_FS_TOTAL_BLOCKS; i++) {
        out_blocks[i] = 0xFFFFu;
    }
    int max_idx = -1;
    int found = 0;
    for (uint16_t blk = 0; blk < NC1020_FS_TOTAL_BLOCKS; blk++) {
        const uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
        uint16_t ino = 0;
        uint8_t chunk = 0;
        if (classify_bat_entry(blk, bat_blk, ent, &ino, &chunk) == BLK_ACTIVE_CHUNK &&
            ino == target_inode) {
            out_blocks[chunk] = blk;
            if ((int) chunk > max_idx) {
                max_idx = (int) chunk;
            }
            found++;
        }
    }
    if (found == 0) {
        *out_count = 0;
        return NC1020_FS_ERR_NOT_FOUND;
    }
    *out_count = max_idx + 1;
    return NC1020_FS_OK;
}

static uint16_t count_inode_blocks(const uint8_t *bat_ptr, uint16_t bat_blk, uint16_t target_inode) {
    uint16_t count = 0;
    for (uint16_t blk = 0; blk < NC1020_FS_TOTAL_BLOCKS; blk++) {
        const uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
        uint16_t ino = 0;
        if (classify_bat_entry(blk, bat_blk, ent, &ino, NULL) == BLK_ACTIVE_CHUNK &&
            ino == target_inode) {
            count++;
        }
    }
    return count;
}

static bool check_has_open_fcb(const uint8_t *ram) {
    if (ram == NULL || ram[0x0460] != 0xA5u) {
        return false;
    }
    for (size_t i = 0; i < 4u; i++) {
        const uint8_t *fcb = ram + 0x1297u + (i * 16u);
        if ((fcb[0] | fcb[1]) != 0u) {
            return true;
        }
    }
    return false;
}

static void sync_ram_fs_pointers(uint8_t *nor, uint16_t bat_blk) {
    uint8_t *ram = get_ram_buffer();
    if (ram == NULL) {
        return;
    }
    const uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);
    uint16_t root_chunks[NC1020_FS_TOTAL_BLOCKS];
    int root_cnt = 0;
    if (collect_inode_chunks(bat_ptr, bat_blk, NC1020_FS_ROOT_INODE, root_chunks, &root_cnt) != NC1020_FS_OK ||
        root_cnt <= 0 || root_chunks[0] == 0xFFFFu) {
        return;
    }
    uint16_t root_blk = root_chunks[0];

    uint16_t cur_ram_bat = (uint16_t) ram[0x0862] | ((uint16_t) ram[0x0863] << 8u);
    if (ram[0x0460] != 0xA5u && (cur_ram_bat == 0u || cur_ram_bat >= NC1020_FS_TOTAL_BLOCKS)) {
        return;
    }

    ram[0x0862] = (uint8_t) (bat_blk & 0xFFu);
    ram[0x0863] = (uint8_t) ((bat_blk >> 8u) & 0xFFu);
    ram[0x0864] = (uint8_t) (root_blk & 0xFFu);
    ram[0x0865] = (uint8_t) ((root_blk >> 8u) & 0xFFu);

    uint16_t cwd_inode = (uint16_t) ram[0x13DC] | ((uint16_t) ram[0x13DD] << 8u);
    if (cwd_inode == NC1020_FS_ROOT_INODE) {
        ram[0x13DA] = (uint8_t) (root_blk & 0xFFu);
        ram[0x13DB] = (uint8_t) ((root_blk >> 8u) & 0xFFu);
    } else {
        uint16_t cwd_chunks[NC1020_FS_TOTAL_BLOCKS];
        int cwd_cnt = 0;
        if (collect_inode_chunks(bat_ptr, bat_blk, cwd_inode, cwd_chunks, &cwd_cnt) == NC1020_FS_OK &&
            cwd_cnt > 0 && cwd_chunks[0] != 0xFFFFu) {
            uint16_t cwd_blk = cwd_chunks[0];
            ram[0x13DA] = (uint8_t) (cwd_blk & 0xFFu);
            ram[0x13DB] = (uint8_t) ((cwd_blk >> 8u) & 0xFFu);
        } else {
            ram[0x13DC] = 0x00u;
            ram[0x13DD] = 0x00u;
            ram[0x13DA] = (uint8_t) (root_blk & 0xFFu);
            ram[0x13DB] = (uint8_t) ((root_blk >> 8u) & 0xFFu);
            ram[0x13DE] = 0xF0u;
            ram[0x13DF] = 0xFFu;
        }
    }
}

static void gc_reclaim_dirty_blocks(uint8_t *nor, uint16_t bat_blk) {
    uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);
    bool reclaimed = false;
    for (uint16_t blk = 0; blk < NC1020_FS_TOTAL_BLOCKS; blk++) {
        uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
        if (classify_bat_entry(blk, bat_blk, ent, NULL, NULL) == BLK_DIRTY) {
            memset(nor + ((size_t) blk * NC1020_FS_BLOCK_SIZE), 0xFF, NC1020_FS_BLOCK_SIZE);
            ent[0] = 0xFFu;
            ent[1] = 0xFFu;
            ent[2] = 0xFFu;
            ent[3] = 0xFFu;
            reclaimed = true;
        }
    }
    if (reclaimed) {
        uint16_t wear = (uint16_t) bat_ptr[BAT_OFFSET_WEAR] |
                        ((uint16_t) bat_ptr[BAT_OFFSET_WEAR + 1u] << 8u);
        if (wear < 0xFFFEu) {
            wear++;
        }
        bat_ptr[BAT_OFFSET_WEAR] = (uint8_t) (wear & 0xFFu);
        bat_ptr[BAT_OFFSET_WEAR + 1u] = (uint8_t) ((wear >> 8u) & 0xFFu);
    }
}

static bool is_active_dir_slot(const uint8_t slot[DIR_SLOT_BYTES]) {
    return ((slot[0] & 0xE0u) == 0xC0u) && ((slot[1] & 0x80u) != 0u);
}

static int format_padded_name16(
    const uint8_t *name_gbk,
    size_t name_len,
    uint8_t out_padded[NC1020_FS_MAX_NAME_BYTES]
) {
    if (name_gbk == NULL || name_len == 0u) {
        return NC1020_FS_ERR_NAME_INVALID;
    }
    while (name_len > 0u && name_gbk[name_len - 1u] == 0x20u) {
        name_len--;
    }
    size_t start = 0u;
    while (start < name_len && name_gbk[start] == 0x20u) {
        start++;
    }
    name_len -= start;
    name_gbk += start;
    if (name_len == 0u || name_len > NC1020_FS_MAX_NAME_BYTES) {
        return NC1020_FS_ERR_NAME_INVALID;
    }
    if ((name_len == 1u && name_gbk[0] == '.') ||
        (name_len == 2u && name_gbk[0] == '.' && name_gbk[1] == '.')) {
        return NC1020_FS_ERR_NAME_INVALID;
    }
    for (size_t i = 0; i < name_len; i++) {
        uint8_t c = name_gbk[i];
        if (c < 0x20u || c == '/' || c == '\\' || c == ':' ||
            c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
            return NC1020_FS_ERR_NAME_INVALID;
        }
    }
    memset(out_padded, 0x20, NC1020_FS_MAX_NAME_BYTES);
    memcpy(out_padded, name_gbk, name_len);
    return NC1020_FS_OK;
}

static void get_current_wqx_date(uint8_t out_date[3]) {
    time_t now = time(NULL);
    struct tm tm_val;
    memset(&tm_val, 0, sizeof(tm_val));
    if (localtime_r(&now, &tm_val) != NULL) {
        int year = tm_val.tm_year + 1900;
        if (year < 1990) year = 2002;
        if (year > 2099) year = 2099;
        int month = tm_val.tm_mon;
        if (month < 0 || month > 11) month = 0;
        int day = tm_val.tm_mday - 1;
        if (day < 0 || day > 30) day = 0;
        out_date[0] = (uint8_t) (year - 1881);
        out_date[1] = (uint8_t) month;
        out_date[2] = (uint8_t) day;
    } else {
        out_date[0] = (uint8_t) (2002 - 1881);
        out_date[1] = 0u;
        out_date[2] = 0u;
    }
}

static int find_free_inode(const uint8_t *bat_ptr, uint16_t bat_blk, uint16_t *out_inode) {
    bool used[MAX_INODE_ID + 1u];
    memset(used, 0, sizeof(used));
    used[0] = true;
    for (uint16_t blk = 0; blk < NC1020_FS_TOTAL_BLOCKS; blk++) {
        const uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
        uint16_t ino = 0;
        if (classify_bat_entry(blk, bat_blk, ent, &ino, NULL) == BLK_ACTIVE_CHUNK &&
            ino <= MAX_INODE_ID) {
            used[ino] = true;
        }
    }
    for (uint16_t ino = 1u; ino <= MAX_INODE_ID; ino++) {
        if (!used[ino]) {
            *out_inode = ino;
            return NC1020_FS_OK;
        }
    }
    return NC1020_FS_ERR_NO_SPACE;
}

static int allocate_free_blocks(
    uint8_t *bat_ptr,
    uint16_t bat_blk,
    int count_needed,
    uint16_t *out_blks
) {
    int found = 0;
    for (uint16_t blk = 0x20u; blk < NC1020_FS_TOTAL_BLOCKS && found < count_needed; blk++) {
        const uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
        if (classify_bat_entry(blk, bat_blk, ent, NULL, NULL) == BLK_FREE) {
            out_blks[found++] = blk;
        }
    }
    return (found == count_needed) ? NC1020_FS_OK : NC1020_FS_ERR_NO_SPACE;
}

static void write_bat_entry(
    uint8_t *bat_ptr,
    uint16_t blk,
    uint16_t inode,
    uint8_t chunk_idx,
    uint8_t blk_type
) {
    uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
    ent[0] = (uint8_t) (inode & 0xFFu);
    ent[1] = (uint8_t) (0x5Eu | ((inode >> 8u) & 0x01u));
    ent[2] = chunk_idx;
    ent[3] = blk_type;
}

static void invalidate_inode_blocks(uint8_t *bat_ptr, uint16_t bat_blk, uint16_t target_inode) {
    for (uint16_t blk = 0; blk < NC1020_FS_TOTAL_BLOCKS; blk++) {
        uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
        uint16_t ino = 0;
        if (classify_bat_entry(blk, bat_blk, ent, &ino, NULL) == BLK_ACTIVE_CHUNK &&
            ino == target_inode) {
            ent[1] &= 0xBFu;
        }
    }
}

static int find_or_compact_dir_free_slot(uint8_t *dir_block_ptr) {
    for (int s = 1; s < (int) SLOTS_PER_BLOCK; s++) {
        uint8_t *slot = dir_block_ptr + ((size_t) s * DIR_SLOT_BYTES);
        if (slot[0] == 0xFFu) {
            return s;
        }
    }
    uint8_t compacted[NC1020_FS_BLOCK_SIZE];
    memset(compacted, 0xFF, sizeof(compacted));
    memcpy(compacted, dir_block_ptr, DIR_SLOT_BYTES);
    int dst = 1;
    for (int s = 1; s < (int) SLOTS_PER_BLOCK; s++) {
        const uint8_t *slot = dir_block_ptr + ((size_t) s * DIR_SLOT_BYTES);
        if (slot[0] == 0xFFu) {
            break;
        }
        if (is_active_dir_slot(slot)) {
            if (dst >= (int) SLOTS_PER_BLOCK) {
                return -1;
            }
            memcpy(compacted + ((size_t) dst * DIR_SLOT_BYTES), slot, DIR_SLOT_BYTES);
            dst++;
        }
    }
    if (dst >= (int) SLOTS_PER_BLOCK) {
        return -1;
    }
    memcpy(dir_block_ptr, compacted, sizeof(compacted));
    return dst;
}

static int get_dir_block_ptr(
    uint8_t *nor,
    const uint8_t *bat_ptr,
    uint16_t bat_blk,
    uint16_t dir_inode,
    uint8_t **out_dir_ptr
) {
    uint16_t chunks[NC1020_FS_TOTAL_BLOCKS];
    int chunk_cnt = 0;
    int rc = collect_inode_chunks(bat_ptr, bat_blk, dir_inode, chunks, &chunk_cnt);
    if (rc != NC1020_FS_OK || chunk_cnt <= 0 || chunks[0] == 0xFFFFu) {
        return NC1020_FS_ERR_NOT_FOUND;
    }
    *out_dir_ptr = nor + ((size_t) chunks[0] * NC1020_FS_BLOCK_SIZE);
    return NC1020_FS_OK;
}

int nc1020_fs_get_info(nc1020_fs_info_t *out_info) {
    if (out_info == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    memset(out_info, 0, sizeof(*out_info));
    const uint8_t *nor = get_nor_buffer();
    if (nor == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    int bat_blk = find_bat_block(nor);
    if (bat_blk < 0) {
        return NC1020_FS_ERR_CORRUPT;
    }
    const uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);

    uint16_t root_chunks[NC1020_FS_TOTAL_BLOCKS];
    int root_cnt = 0;
    if (collect_inode_chunks(bat_ptr, (uint16_t) bat_blk, NC1020_FS_ROOT_INODE, root_chunks, &root_cnt) != NC1020_FS_OK ||
        root_cnt <= 0 || root_chunks[0] == 0xFFFFu) {
        return NC1020_FS_ERR_CORRUPT;
    }

    out_info->bat_block = (uint16_t) bat_blk;
    out_info->root_block = root_chunks[0];
    out_info->total_blocks = (uint16_t) NC1020_FS_TOTAL_BLOCKS;
    out_info->wear_count = (uint16_t) bat_ptr[BAT_OFFSET_WEAR] |
                           ((uint16_t) bat_ptr[BAT_OFFSET_WEAR + 1u] << 8u);
    out_info->has_open_fcb = check_has_open_fcb(get_ram_buffer());

    for (uint16_t blk = 0; blk < NC1020_FS_TOTAL_BLOCKS; blk++) {
        const uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
        switch (classify_bat_entry(blk, (uint16_t) bat_blk, ent, NULL, NULL)) {
            case BLK_FREE:
                out_info->free_blocks++;
                break;
            case BLK_RESERVED:
                out_info->reserved_blocks++;
                break;
            case BLK_BAT:
            case BLK_ACTIVE_CHUNK:
                out_info->used_blocks++;
                break;
            case BLK_DIRTY:
                out_info->dirty_blocks++;
                break;
        }
    }
    return NC1020_FS_OK;
}

int nc1020_fs_list_dir(
    uint16_t dir_inode,
    nc1020_fs_entry_t *out_entries,
    int max_entries,
    int *out_count
) {
    if (out_count == NULL || (max_entries > 0 && out_entries == NULL)) {
        return NC1020_FS_ERR_CORRUPT;
    }
    *out_count = 0;
    uint8_t *nor = get_nor_buffer();
    if (nor == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    int bat_blk = find_bat_block(nor);
    if (bat_blk < 0) {
        return NC1020_FS_ERR_CORRUPT;
    }
    const uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);

    uint8_t *dir_ptr = NULL;
    int rc = get_dir_block_ptr(nor, bat_ptr, (uint16_t) bat_blk, dir_inode, &dir_ptr);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    int count = 0;
    for (size_t s = 1u; s < SLOTS_PER_BLOCK; s++) {
        const uint8_t *slot = dir_ptr + (s * DIR_SLOT_BYTES);
        if (slot[0] == 0xFFu) {
            break;
        }
        if (!is_active_dir_slot(slot)) {
            continue;
        }
        if (count < max_entries && out_entries != NULL) {
            nc1020_fs_entry_t *dst = &out_entries[count];
            memset(dst, 0, sizeof(*dst));
            size_t len = NC1020_FS_MAX_NAME_BYTES;
            while (len > 0u && slot[2u + len - 1u] == 0x20u) {
                len--;
            }
            memcpy(dst->name_gbk, slot + 2u, len);
            dst->name_len = (uint8_t) len;
            dst->attr = slot[0];
            dst->attr2 = slot[1];
            dst->year = (uint16_t) (1881u + slot[18]);
            dst->month = (uint8_t) (slot[19] + 1u);
            dst->day = (uint8_t) (slot[20] + 1u);
            dst->size = (uint32_t) slot[24] |
                        ((uint32_t) slot[25] << 8u) |
                        ((uint32_t) slot[26] << 16u);
            dst->inode = (uint16_t) slot[27] | ((uint16_t) slot[28] << 8u);
            dst->blocks = count_inode_blocks(bat_ptr, (uint16_t) bat_blk, dst->inode);
        }
        count++;
    }
    *out_count = count;
    return NC1020_FS_OK;
}

int nc1020_fs_read_file(
    uint16_t inode,
    uint8_t *out_buf,
    uint32_t buf_capacity,
    uint32_t expected_size,
    uint32_t *out_bytes_read
) {
    if (out_bytes_read == NULL || (expected_size > 0u && out_buf == NULL)) {
        return NC1020_FS_ERR_CORRUPT;
    }
    *out_bytes_read = 0u;
    const uint8_t *nor = get_nor_buffer();
    if (nor == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    int bat_blk = find_bat_block(nor);
    if (bat_blk < 0) {
        return NC1020_FS_ERR_CORRUPT;
    }
    const uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);

    uint16_t chunks[NC1020_FS_TOTAL_BLOCKS];
    int chunk_cnt = 0;
    int rc = collect_inode_chunks(bat_ptr, (uint16_t) bat_blk, inode, chunks, &chunk_cnt);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    uint32_t to_read = expected_size;
    if (to_read > buf_capacity) {
        to_read = buf_capacity;
    }

    uint32_t copied = 0u;
    for (int i = 0; i < chunk_cnt && copied < to_read; i++) {
        uint32_t remain = to_read - copied;
        uint32_t step = (remain < NC1020_FS_BLOCK_SIZE) ? remain : NC1020_FS_BLOCK_SIZE;
        if (chunks[i] == 0xFFFFu) {
            memset(out_buf + copied, 0xFF, step);
        } else {
            memcpy(
                out_buf + copied,
                nor + ((size_t) chunks[i] * NC1020_FS_BLOCK_SIZE),
                step
            );
        }
        copied += step;
    }
    *out_bytes_read = copied;
    return NC1020_FS_OK;
}

int nc1020_fs_mkdir(
    uint16_t parent_inode,
    const uint8_t *name_gbk,
    size_t name_len,
    bool save_states,
    uint16_t *out_inode
) {
    uint8_t padded_name[NC1020_FS_MAX_NAME_BYTES];
    int rc = format_padded_name16(name_gbk, name_len, padded_name);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    uint8_t *nor = get_nor_buffer();
    if (nor == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    int bat_blk = find_bat_block(nor);
    if (bat_blk < 0) {
        return NC1020_FS_ERR_CORRUPT;
    }
    uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);

    uint8_t *parent_ptr = NULL;
    rc = get_dir_block_ptr(nor, bat_ptr, (uint16_t) bat_blk, parent_inode, &parent_ptr);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    for (size_t s = 1u; s < SLOTS_PER_BLOCK; s++) {
        const uint8_t *slot = parent_ptr + (s * DIR_SLOT_BYTES);
        if (slot[0] == 0xFFu) {
            break;
        }
        if (is_active_dir_slot(slot) && memcmp(slot + 2u, padded_name, NC1020_FS_MAX_NAME_BYTES) == 0) {
            if ((slot[0] & 0x08u) == 0u && out_inode != NULL) {
                *out_inode = (uint16_t) slot[27] | ((uint16_t) slot[28] << 8u);
            }
            return NC1020_FS_ERR_EXISTS;
        }
    }

    gc_reclaim_dirty_blocks(nor, (uint16_t) bat_blk);

    uint16_t new_inode = 0u;
    rc = find_free_inode(bat_ptr, (uint16_t) bat_blk, &new_inode);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    uint16_t alloc_blks[2];
    if (allocate_free_blocks(bat_ptr, (uint16_t) bat_blk, 2, alloc_blks) != NC1020_FS_OK) {
        return NC1020_FS_ERR_NO_SPACE;
    }
    uint16_t dir_blk = alloc_blks[0];

    int slot_idx = find_or_compact_dir_free_slot(parent_ptr);
    if (slot_idx < 1) {
        return NC1020_FS_ERR_DIR_FULL;
    }

    uint8_t *new_dir_ptr = nor + ((size_t) dir_blk * NC1020_FS_BLOCK_SIZE);
    memset(new_dir_ptr, 0xFF, NC1020_FS_BLOCK_SIZE);
    new_dir_ptr[0] = (uint8_t) (parent_inode & 0xFFu);
    new_dir_ptr[1] = (uint8_t) ((parent_inode >> 8u) & 0xFFu);

    write_bat_entry(bat_ptr, dir_blk, new_inode, 0u, 0x04u);

    uint8_t *slot = parent_ptr + ((size_t) slot_idx * DIR_SLOT_BYTES);
    memset(slot, 0xFF, DIR_SLOT_BYTES);
    slot[0] = 0xC0u;
    slot[1] = 0xFFu;
    memcpy(slot + 2u, padded_name, NC1020_FS_MAX_NAME_BYTES);
    get_current_wqx_date(slot + 18u);
    slot[24] = 0x00u;
    slot[25] = 0x10u;
    slot[26] = 0x00u;
    slot[27] = (uint8_t) (new_inode & 0xFFu);
    slot[28] = (uint8_t) ((new_inode >> 8u) & 0xFFu);

    if (out_inode != NULL) {
        *out_inode = new_inode;
    }

    sync_ram_fs_pointers(nor, (uint16_t) bat_blk);
    flush_nor_and_states(save_states);
    return NC1020_FS_OK;
}

int nc1020_fs_write_file(
    uint16_t dir_inode,
    const uint8_t *name_gbk,
    size_t name_len,
    const uint8_t *data,
    uint32_t size,
    bool overwrite,
    bool save_states,
    uint16_t *out_inode
) {
    if (size > 0u && data == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    if (size > 220u * NC1020_FS_BLOCK_SIZE) {
        return NC1020_FS_ERR_NO_SPACE;
    }

    uint8_t padded_name[NC1020_FS_MAX_NAME_BYTES];
    int rc = format_padded_name16(name_gbk, name_len, padded_name);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    uint8_t *nor = get_nor_buffer();
    if (nor == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    int bat_blk = find_bat_block(nor);
    if (bat_blk < 0) {
        return NC1020_FS_ERR_CORRUPT;
    }
    uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);

    uint8_t *dir_ptr = NULL;
    rc = get_dir_block_ptr(nor, bat_ptr, (uint16_t) bat_blk, dir_inode, &dir_ptr);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    int existing_slot = -1;
    uint16_t existing_inode = 0u;
    uint8_t preserved_attr = 0xC8u;
    uint8_t preserved_attr2 = 0xDFu;
    for (size_t s = 1u; s < SLOTS_PER_BLOCK; s++) {
        const uint8_t *slot = dir_ptr + (s * DIR_SLOT_BYTES);
        if (slot[0] == 0xFFu) {
            break;
        }
        if (is_active_dir_slot(slot) && memcmp(slot + 2u, padded_name, NC1020_FS_MAX_NAME_BYTES) == 0) {
            if ((slot[0] & 0x08u) == 0u) {
                return NC1020_FS_ERR_IS_DIR;
            }
            if (!overwrite) {
                return NC1020_FS_ERR_EXISTS;
            }
            existing_slot = (int) s;
            existing_inode = (uint16_t) slot[27] | ((uint16_t) slot[28] << 8u);
            preserved_attr = slot[0];
            preserved_attr2 = slot[1];
            break;
        }
    }

    int blocks_needed = (size == 0u) ? 1 : (int) ((size + NC1020_FS_BLOCK_SIZE - 1u) / NC1020_FS_BLOCK_SIZE);

    gc_reclaim_dirty_blocks(nor, (uint16_t) bat_blk);

    int free_now = 0;
    for (uint16_t blk = 0x20u; blk < NC1020_FS_TOTAL_BLOCKS; blk++) {
        const uint8_t *ent = bat_ptr + BAT_OFFSET_ENTRIES + ((size_t) blk * 4u);
        if (classify_bat_entry(blk, (uint16_t) bat_blk, ent, NULL, NULL) == BLK_FREE) {
            free_now++;
        }
    }
    int reclaimable_old = (existing_slot >= 1)
        ? (int) count_inode_blocks(bat_ptr, (uint16_t) bat_blk, existing_inode)
        : 0;
    if (free_now + reclaimable_old < blocks_needed + 1) {
        return NC1020_FS_ERR_NO_SPACE;
    }

    if (existing_slot < 1) {
        int active_count = 0;
        for (size_t s = 1u; s < SLOTS_PER_BLOCK; s++) {
            const uint8_t *slot = dir_ptr + (s * DIR_SLOT_BYTES);
            if (slot[0] == 0xFFu) break;
            if (is_active_dir_slot(slot)) active_count++;
        }
        if (active_count >= (int) NC1020_FS_MAX_DIR_ENTRIES) {
            return NC1020_FS_ERR_DIR_FULL;
        }
    }

    uint16_t target_inode = 0u;
    if (existing_slot >= 1) {
        dir_ptr[(size_t) existing_slot * DIR_SLOT_BYTES] &= 0x7Fu;
        invalidate_inode_blocks(bat_ptr, (uint16_t) bat_blk, existing_inode);
        if (free_now < blocks_needed + 1) {
            gc_reclaim_dirty_blocks(nor, (uint16_t) bat_blk);
        }
        target_inode = existing_inode;
    } else {
        rc = find_free_inode(bat_ptr, (uint16_t) bat_blk, &target_inode);
        if (rc != NC1020_FS_OK) {
            return rc;
        }
    }

    uint16_t alloc_blks[NC1020_FS_TOTAL_BLOCKS];
    rc = allocate_free_blocks(bat_ptr, (uint16_t) bat_blk, blocks_needed, alloc_blks);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    uint32_t offset = 0u;
    for (int i = 0; i < blocks_needed; i++) {
        uint8_t *blk_ptr = nor + ((size_t) alloc_blks[i] * NC1020_FS_BLOCK_SIZE);
        memset(blk_ptr, 0xFF, NC1020_FS_BLOCK_SIZE);
        if (offset < size) {
            uint32_t remain = size - offset;
            uint32_t step = (remain < NC1020_FS_BLOCK_SIZE) ? remain : NC1020_FS_BLOCK_SIZE;
            memcpy(blk_ptr, data + offset, step);
            offset += step;
        }
        write_bat_entry(bat_ptr, alloc_blks[i], target_inode, (uint8_t) i, 0xC2u);
    }

    int slot_idx = find_or_compact_dir_free_slot(dir_ptr);
    if (slot_idx < 1) {
        return NC1020_FS_ERR_DIR_FULL;
    }

    uint8_t *slot = dir_ptr + ((size_t) slot_idx * DIR_SLOT_BYTES);
    memset(slot, 0xFF, DIR_SLOT_BYTES);
    slot[0] = preserved_attr;
    slot[1] = preserved_attr2;
    memcpy(slot + 2u, padded_name, NC1020_FS_MAX_NAME_BYTES);
    get_current_wqx_date(slot + 18u);
    slot[24] = (uint8_t) (size & 0xFFu);
    slot[25] = (uint8_t) ((size >> 8u) & 0xFFu);
    slot[26] = (uint8_t) ((size >> 16u) & 0xFFu);
    slot[27] = (uint8_t) (target_inode & 0xFFu);
    slot[28] = (uint8_t) ((target_inode >> 8u) & 0xFFu);

    if (out_inode != NULL) {
        *out_inode = target_inode;
    }

    sync_ram_fs_pointers(nor, (uint16_t) bat_blk);
    flush_nor_and_states(save_states);
    return NC1020_FS_OK;
}

int nc1020_fs_delete(
    uint16_t dir_inode,
    uint16_t target_inode,
    bool save_states
) {
    if (target_inode == NC1020_FS_ROOT_INODE) {
        return NC1020_FS_ERR_IS_DIR;
    }
    uint8_t *nor = get_nor_buffer();
    if (nor == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    int bat_blk = find_bat_block(nor);
    if (bat_blk < 0) {
        return NC1020_FS_ERR_CORRUPT;
    }
    uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);

    uint8_t *dir_ptr = NULL;
    int rc = get_dir_block_ptr(nor, bat_ptr, (uint16_t) bat_blk, dir_inode, &dir_ptr);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    int found_slot = -1;
    bool is_dir = false;
    for (size_t s = 1u; s < SLOTS_PER_BLOCK; s++) {
        const uint8_t *slot = dir_ptr + (s * DIR_SLOT_BYTES);
        if (slot[0] == 0xFFu) {
            break;
        }
        if (is_active_dir_slot(slot)) {
            uint16_t ino = (uint16_t) slot[27] | ((uint16_t) slot[28] << 8u);
            if (ino == target_inode) {
                found_slot = (int) s;
                is_dir = ((slot[0] & 0x08u) == 0u);
                break;
            }
        }
    }
    if (found_slot < 1) {
        return NC1020_FS_ERR_NOT_FOUND;
    }

    if (is_dir) {
        int child_count = 0;
        rc = nc1020_fs_list_dir(target_inode, NULL, 0, &child_count);
        if (rc == NC1020_FS_OK && child_count > 0) {
            return NC1020_FS_ERR_NOT_EMPTY;
        }
    }

    dir_ptr[(size_t) found_slot * DIR_SLOT_BYTES] &= 0x7Fu;
    invalidate_inode_blocks(bat_ptr, (uint16_t) bat_blk, target_inode);
    gc_reclaim_dirty_blocks(nor, (uint16_t) bat_blk);

    sync_ram_fs_pointers(nor, (uint16_t) bat_blk);
    flush_nor_and_states(save_states);
    return NC1020_FS_OK;
}

int nc1020_fs_rename(
    uint16_t dir_inode,
    uint16_t target_inode,
    const uint8_t *new_name_gbk,
    size_t new_name_len,
    bool save_states
) {
    uint8_t padded_name[NC1020_FS_MAX_NAME_BYTES];
    int rc = format_padded_name16(new_name_gbk, new_name_len, padded_name);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    uint8_t *nor = get_nor_buffer();
    if (nor == NULL) {
        return NC1020_FS_ERR_CORRUPT;
    }
    int bat_blk = find_bat_block(nor);
    if (bat_blk < 0) {
        return NC1020_FS_ERR_CORRUPT;
    }
    const uint8_t *bat_ptr = nor + ((size_t) bat_blk * NC1020_FS_BLOCK_SIZE);

    uint8_t *dir_ptr = NULL;
    rc = get_dir_block_ptr(nor, bat_ptr, (uint16_t) bat_blk, dir_inode, &dir_ptr);
    if (rc != NC1020_FS_OK) {
        return rc;
    }

    int found_slot = -1;
    for (size_t s = 1u; s < SLOTS_PER_BLOCK; s++) {
        const uint8_t *slot = dir_ptr + (s * DIR_SLOT_BYTES);
        if (slot[0] == 0xFFu) {
            break;
        }
        if (!is_active_dir_slot(slot)) {
            continue;
        }
        uint16_t ino = (uint16_t) slot[27] | ((uint16_t) slot[28] << 8u);
        if (ino == target_inode) {
            found_slot = (int) s;
            if (memcmp(slot + 2u, padded_name, NC1020_FS_MAX_NAME_BYTES) == 0) {
                return NC1020_FS_OK;
            }
        } else if (memcmp(slot + 2u, padded_name, NC1020_FS_MAX_NAME_BYTES) == 0) {
            return NC1020_FS_ERR_EXISTS;
        }
    }
    if (found_slot < 1) {
        return NC1020_FS_ERR_NOT_FOUND;
    }

    uint8_t saved_slot[DIR_SLOT_BYTES];
    memcpy(saved_slot, dir_ptr + ((size_t) found_slot * DIR_SLOT_BYTES), DIR_SLOT_BYTES);
    memcpy(saved_slot + 2u, padded_name, NC1020_FS_MAX_NAME_BYTES);

    dir_ptr[(size_t) found_slot * DIR_SLOT_BYTES] &= 0x7Fu;
    int new_slot = find_or_compact_dir_free_slot(dir_ptr);
    if (new_slot < 1) {
        dir_ptr[(size_t) found_slot * DIR_SLOT_BYTES] = saved_slot[0];
        return NC1020_FS_ERR_DIR_FULL;
    }
    memcpy(dir_ptr + ((size_t) new_slot * DIR_SLOT_BYTES), saved_slot, DIR_SLOT_BYTES);

    sync_ram_fs_pointers(nor, (uint16_t) bat_blk);
    flush_nor_and_states(save_states);
    return NC1020_FS_OK;
}
