#include "nc1020_dsp.h"
#include "nc1020_sound.h"
#include <string.h>

// Sunplus SPDS104A speech DSP synthesizer (CELP / Word-Syllable TTS / 8kHz PCM)
// Ported to C11 from Lee's Pc1000emux & wangyu-'s wqxdsp / NC2000 implementation.

#define NONE_MODE 0
#define CELP_MODE 1
#define WORD_MODE 2
#define PCM_MODE  4

#define ORDER 10
#define FADE_RANGE 12
#define WORD_BUF_MAX (240 * 128)

#define DSP_SLEEP_FLAG            0x80u
#define DSP_RETURN_DATA_READY_FLAG 0x40u
#define DSP_DATA_UNFETCHED_FLAG   0x30u

#define DSP_WAKEUP_CTRL 0x80u
#define DSP_RESET_CTRL  0x40u

typedef struct {
    int32_t D[ORDER + 1];
    int32_t ai[ORDER + 1];
} fixfilter_t;

typedef struct {
    int32_t Lindex;
    int32_t Gindex;
    int32_t Grid;
    int32_t Pos[2];
    int32_t Sign[2];
} gain_shape_t;

static const int32_t LSP_SEARCH_PTR[ORDER] = {
    0, 8, 24, 40, 56, 72, 80, 88, 96, 104
};

static const int32_t INTER_WEIGHT[8] = {
    3, 2, 1, 0,
    1, 2, 3, 4
};

static const int16_t WSINC[16] = {
    -235, -383,
    1050, 1629,
    -3571, -5216,
    12714, 26674,
    26674, 12714,
    -5216, -3571,
    1629, 1050,
    -383, -235
};

static const int16_t PGAIN[16] = {
    0x0000, 0x04cc, 0x0999, 0x0e66, 0x1333, 0x1800, 0x1ccc, 0x2199,
    0x2666, 0x2b33, 0x3000, 0x34cc, 0x3999, 0x3e66, 0x4333, 0x4800
};

static const int16_t CGAIN[16] = {
    0x0000, 0x0001, 0x0003, 0x000D, 0x0023, 0x0040, 0x0062, 0x0088,
    0x00B2, 0x00E0, 0x0116, 0x0154, 0x01A2, 0x0208, 0x0294, 0x0366
};

static const int16_t LSPINDEX[112] = {
    0x0333, 0x0570, 0x0733, 0x0800, 0x08f5, 0x0ae1, 0x0d70, 0x1000,
    0x06b8, 0x0785, 0x087a, 0x0970, 0x0a66, 0x0b85, 0x0ccc, 0x0e14,
    0x0f5c, 0x10a3, 0x11eb, 0x1385, 0x1570, 0x17ae, 0x19eb, 0x1c28,
    0x0d70, 0x0eb8, 0x1000, 0x1147, 0x12b8, 0x147a, 0x168f, 0x18cc,
    0x1b33, 0x1e66, 0x2199, 0x24cc, 0x2800, 0x2b33, 0x2e66, 0x3199,
    0x13d7, 0x151e, 0x170a, 0x1970, 0x1c28, 0x1f0a, 0x228f, 0x2570,
    0x28a3, 0x2bd7, 0x2f0a, 0x323d, 0x3570, 0x38a3, 0x3bd7, 0x3f0a,
    0x2000, 0x2199, 0x2428, 0x26b8, 0x291e, 0x2b33, 0x2dc2, 0x3051,
    0x32e1, 0x3570, 0x3800, 0x3b33, 0x3e66, 0x4199, 0x44cc, 0x4800,
    0x2f0a, 0x323d, 0x3614, 0x3a8f, 0x4000, 0x4666, 0x4ccc, 0x5333,
    0x3999, 0x3c28, 0x3eb8, 0x4333, 0x4999, 0x4f5c, 0x5666, 0x5ccc,
    0x4733, 0x4ccc, 0x50cc, 0x54cc, 0x5999, 0x5e66, 0x64cc, 0x6b33,
    0x5851, 0x5c28, 0x6000, 0x6333, 0x6666, 0x69eb, 0x6dc2, 0x7199,
    0x6614, 0x68a3, 0x6b33, 0x6d70, 0x6fae, 0x72e1, 0x76b8, 0x7a8f
};

static const int16_t COSTABLE[128] = {
    16384, 16379, 16364, 16339, 16305, 16260, 16206, 16142,
    16069, 15985, 15892, 15790, 15678, 15557, 15426, 15286,
    15136, 14978, 14810, 14634, 14449, 14255, 14053, 13842,
    13622, 13395, 13159, 12916, 12665, 12406, 12139, 11866,
    11585, 11297, 11002, 10701, 10393, 10079, 9759, 9434,
    9102, 8765, 8423, 8075, 7723, 7366, 7005, 6639,
    6269, 5896, 5519, 5139, 4756, 4369, 3980, 3589,
    3196, 2801, 2404, 2005, 1605, 1205, 803, 402,
    0, -402, -803, -1205, -1605, -2005, -2404, -2801,
    -3196, -3589, -3980, -4369, -4756, -5139, -5519, -5896,
    -6269, -6639, -7005, -7366, -7723, -8075, -8423, -8765,
    -9102, -9434, -9759, -10079, -10393, -10701, -11002, -11297,
    -11585, -11866, -12139, -12406, -12665, -12916, -13159, -13395,
    -13622, -13842, -14053, -14255, -14449, -14634, -14810, -14978,
    -15136, -15286, -15426, -15557, -15678, -15790, -15892, -15985,
    -16069, -16142, -16206, -16260, -16305, -16339, -16364, -16379
};

static const int16_t FIXCODEBOOK[435] = {
    0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008, 0x0009, 0x000a,
    0x000b, 0x000c, 0x000d, 0x000e, 0x000f, 0x0010, 0x0011, 0x0012, 0x0013, 0x0014,
    0x0015, 0x0016, 0x0017, 0x0018, 0x0019, 0x001a, 0x001b, 0x001c, 0x001d, 0x0102,
    0x0103, 0x0104, 0x0105, 0x0106, 0x0107, 0x0108, 0x0109, 0x010a, 0x010b, 0x010c,
    0x010d, 0x010e, 0x010f, 0x0110, 0x0111, 0x0112, 0x0113, 0x0114, 0x0115, 0x0116,
    0x0117, 0x0118, 0x0119, 0x011a, 0x011b, 0x011c, 0x011d, 0x0203, 0x0204, 0x0205,
    0x0206, 0x0207, 0x0208, 0x0209, 0x020a, 0x020b, 0x020c, 0x020d, 0x020e, 0x020f,
    0x0210, 0x0211, 0x0212, 0x0213, 0x0214, 0x0215, 0x0216, 0x0217, 0x0218, 0x0219,
    0x021a, 0x021b, 0x021c, 0x021d, 0x0304, 0x0305, 0x0306, 0x0307, 0x0308, 0x0309,
    0x030a, 0x030b, 0x030c, 0x030d, 0x030e, 0x030f, 0x0310, 0x0311, 0x0312, 0x0313,
    0x0314, 0x0315, 0x0316, 0x0317, 0x0318, 0x0319, 0x031a, 0x031b, 0x031c, 0x031d,
    0x0405, 0x0406, 0x0407, 0x0408, 0x0409, 0x040a, 0x040b, 0x040c, 0x040d, 0x040e,
    0x040f, 0x0410, 0x0411, 0x0412, 0x0413, 0x0414, 0x0415, 0x0416, 0x0417, 0x0418,
    0x0419, 0x041a, 0x041b, 0x041c, 0x041d, 0x0506, 0x0507, 0x0508, 0x0509, 0x050a,
    0x050b, 0x050c, 0x050d, 0x050e, 0x050f, 0x0510, 0x0511, 0x0512, 0x0513, 0x0514,
    0x0515, 0x0516, 0x0517, 0x0518, 0x0519, 0x051a, 0x051b, 0x051c, 0x051d, 0x0607,
    0x0608, 0x0609, 0x060a, 0x060b, 0x060c, 0x060d, 0x060e, 0x060f, 0x0610, 0x0611,
    0x0612, 0x0613, 0x0614, 0x0615, 0x0616, 0x0617, 0x0618, 0x0619, 0x061a, 0x061b,
    0x061c, 0x061d, 0x0708, 0x0709, 0x070a, 0x070b, 0x070c, 0x070d, 0x070e, 0x070f,
    0x0710, 0x0711, 0x0712, 0x0713, 0x0714, 0x0715, 0x0716, 0x0717, 0x0718, 0x0719,
    0x071a, 0x071b, 0x071c, 0x071d, 0x0809, 0x080a, 0x080b, 0x080c, 0x080d, 0x080e,
    0x080f, 0x0810, 0x0811, 0x0812, 0x0813, 0x0814, 0x0815, 0x0816, 0x0817, 0x0818,
    0x0819, 0x081a, 0x081b, 0x081c, 0x081d, 0x090a, 0x090b, 0x090c, 0x090d, 0x090e,
    0x090f, 0x0910, 0x0911, 0x0912, 0x0913, 0x0914, 0x0915, 0x0916, 0x0917, 0x0918,
    0x0919, 0x091a, 0x091b, 0x091c, 0x091d, 0x0a0b, 0x0a0c, 0x0a0d, 0x0a0e, 0x0a0f,
    0x0a10, 0x0a11, 0x0a12, 0x0a13, 0x0a14, 0x0a15, 0x0a16, 0x0a17, 0x0a18, 0x0a19,
    0x0a1a, 0x0a1b, 0x0a1c, 0x0a1d, 0x0b0c, 0x0b0d, 0x0b0e, 0x0b0f, 0x0b10, 0x0b11,
    0x0b12, 0x0b13, 0x0b14, 0x0b15, 0x0b16, 0x0b17, 0x0b18, 0x0b19, 0x0b1a, 0x0b1b,
    0x0b1c, 0x0b1d, 0x0c0d, 0x0c0e, 0x0c0f, 0x0c10, 0x0c11, 0x0c12, 0x0c13, 0x0c14,
    0x0c15, 0x0c16, 0x0c17, 0x0c18, 0x0c19, 0x0c1a, 0x0c1b, 0x0c1c, 0x0c1d, 0x0d0e,
    0x0d0f, 0x0d10, 0x0d11, 0x0d12, 0x0d13, 0x0d14, 0x0d15, 0x0d16, 0x0d17, 0x0d18,
    0x0d19, 0x0d1a, 0x0d1b, 0x0d1c, 0x0d1d, 0x0e0f, 0x0e10, 0x0e11, 0x0e12, 0x0e13,
    0x0e14, 0x0e15, 0x0e16, 0x0e17, 0x0e18, 0x0e19, 0x0e1a, 0x0e1b, 0x0e1c, 0x0e1d,
    0x0f10, 0x0f11, 0x0f12, 0x0f13, 0x0f14, 0x0f15, 0x0f16, 0x0f17, 0x0f18, 0x0f19,
    0x0f1a, 0x0f1b, 0x0f1c, 0x0f1d, 0x1011, 0x1012, 0x1013, 0x1014, 0x1015, 0x1016,
    0x1017, 0x1018, 0x1019, 0x101a, 0x101b, 0x101c, 0x101d, 0x1112, 0x1113, 0x1114,
    0x1115, 0x1116, 0x1117, 0x1118, 0x1119, 0x111a, 0x111b, 0x111c, 0x111d, 0x1213,
    0x1214, 0x1215, 0x1216, 0x1217, 0x1218, 0x1219, 0x121a, 0x121b, 0x121c, 0x121d,
    0x1314, 0x1315, 0x1316, 0x1317, 0x1318, 0x1319, 0x131a, 0x131b, 0x131c, 0x131d,
    0x1415, 0x1416, 0x1417, 0x1418, 0x1419, 0x141a, 0x141b, 0x141c, 0x141d, 0x1516,
    0x1517, 0x1518, 0x1519, 0x151a, 0x151b, 0x151c, 0x151d, 0x1617, 0x1618, 0x1619,
    0x161a, 0x161b, 0x161c, 0x161d, 0x1718, 0x1719, 0x171a, 0x171b, 0x171c, 0x171d,
    0x1819, 0x181a, 0x181b, 0x181c, 0x181d, 0x191a, 0x191b, 0x191c, 0x191d, 0x1a1b,
    0x1a1c, 0x1a1d, 0x1b1c, 0x1b1d, 0x1c1d
};

// I/O interface state ($20..$23)
static bool _dsp_sleep = false;
static bool _dsp_trans = false;
static bool _dsp_0xd0 = false;
static bool _dsp_0x7001_0x7002 = false;
static bool _dsp_unfetched = false;
static int32_t _dsp_ret_data = -1;
static uint8_t _dsp_data_low = 0;

// Synthesizer core state
static uint8_t _clp_buf[18];
static int32_t _dsp_celp[15];
static int32_t _dsp_celp_off = 0;
static int32_t _dsp_mode = CELP_MODE;

static int32_t _oldindex[ORDER];
static int32_t _newindex[ORDER];
static int32_t _lsp[ORDER];
static int32_t _r[240];
static int32_t _ltp[148];
static int16_t _sout[240];
static int32_t _recursive[60];
static int32_t _period = 0;
static int32_t _frac = 0;
static int32_t _sub_frame_size = 60;
static int32_t _pre_p = 0;
static int32_t _pcount = 0;
static int32_t _ex = 147;
static int32_t _pm = 0;
static bool _lsp_flag = true;
static gain_shape_t _pitch;
static gain_shape_t _inno;
static fixfilter_t _decoder;

static int32_t _delsp[10];
static int32_t _pc[ORDER + 1];
static int32_t _tr[5];
static int32_t _p[5], _q[5], _rx[5], _sx[5];

static int16_t _word_buf[WORD_BUF_MAX];
static int32_t _word_buf_len = 0;
static int32_t _c2 = 0, _c4 = 0, _c8 = 0;
static int32_t _data_cnt = 0;

static inline int32_t rounding(int32_t mul, int32_t p) {
    return (mul + (1 << (p - 1))) >> p;
}

static void dsp_start(void) {
    memset(_ltp, 0, sizeof(_ltp));
    _ex = 147;
    _pm = 0;
}

static void dsp_reset_core(void) {
    _data_cnt = 0;
    _dsp_celp_off = 0;
    dsp_start();
    _word_buf_len = 0;
    _dsp_mode = CELP_MODE;
}

static void root_rs(const int32_t hx_root_in_po[5], int32_t hx_pcoef[5]) {
    int32_t alu = 0;
    int32_t blu, a1, a2;

    for (int i = 0; i < 5; i++) {
        alu -= hx_root_in_po[i];
    }
    hx_pcoef[0] = rounding(alu, 2);

    blu = 0;
    for (int i = 0; i < 4; i++) {
        for (int j = i + 1; j < 5; j++) {
            blu += (hx_root_in_po[i] * hx_root_in_po[j]) >> 15;
        }
    }
    hx_pcoef[1] = blu + 5;

    for (int i = 0; i < 5; i++) {
        _tr[i] = hx_root_in_po[i] << 1;
    }

    blu = 0;
    for (int i = 0; i < 3; i++) {
        for (int j = i + 1; j < 4; j++) {
            for (int k = j + 1; k < 5; k++) {
                a1 = (int32_t)(((int64_t)_tr[i] * _tr[j]) >> 15);
                blu -= (a1 * hx_root_in_po[k]) >> 15;
            }
        }
    }
    hx_pcoef[2] = blu;

    blu = 0;
    for (int i = 0; i < 2; i++) {
        for (int j = i + 1; j < 3; j++) {
            for (int k = j + 1; k < 4; k++) {
                for (int l = k + 1; l < 5; l++) {
                    a1 = (int32_t)(((int64_t)_tr[i] * _tr[j]) >> 15);
                    a2 = (int32_t)(((int64_t)_tr[k] * _tr[l]) >> 15);
                    blu += (a1 * a2) >> 15;
                }
            }
        }
    }
    hx_pcoef[3] = blu;

    a1 = (int32_t)(((int64_t)_tr[0] * _tr[1]) >> 15);
    a2 = (int32_t)(((int64_t)_tr[3] * _tr[4]) >> 15);
    a1 = (a1 * a2) >> 15;
    blu = (a1 * _tr[2]) >> 15;
    blu <<= 1;
    hx_pcoef[4] = -blu;
}

static void fixlsptopc(void) {
    int32_t rx1, sx1, rx2, sx2, rx3, sx3, rx4, sx4;

    for (int i = 0; i < 5; i++) {
        _p[i] = _lsp[2 * i];
        _q[i] = _lsp[2 * i + 1];
    }
    root_rs(_p, _rx);
    root_rs(_q, _sx);

    _pc[0] = 0x0800;
    _pc[1] = (_rx[0] + _sx[0]) >> 1;
    rx1 = 0x2800 + _rx[0] + _rx[1];
    rx2 = rx1 + _rx[2] + _rx[0] * 3;
    rx3 = rx2 + 0x2800 + _rx[3] + (_rx[1] << 1);
    rx4 = rx3 + _rx[4] + _rx[2] + (_rx[0] << 1);
    sx1 = 0x2800 + _sx[1] - _sx[0];
    sx2 = _sx[2] + _sx[0] * 3 - sx1;
    sx3 = _sx[3] + 0x2800 + (_sx[1] << 1) - sx2;
    sx4 = _sx[4] + _sx[2] + (_sx[0] << 1) - sx3;

    _pc[2] = (rx1 + sx1) >> 1;
    _pc[3] = (rx2 + sx2) >> 1;
    _pc[4] = (rx3 + sx3) >> 1;
    _pc[5] = (rx4 + sx4) >> 1;
    _pc[6] = (rx4 - sx4) >> 1;
    _pc[7] = (rx3 - sx3) >> 1;
    _pc[8] = (rx2 - sx2) >> 1;
    _pc[9] = (rx1 - sx1) >> 1;
    _pc[10] = (_rx[0] - _sx[0] + 0x1000) >> 1;

    for (int i = 0; i <= 10; i++) {
        _decoder.ai[i] = _pc[i];
    }
}

static int32_t fixpolefilter(int32_t s) {
    int32_t sum = 0;
    for (int i = 10; i > 0; i--) {
        sum += _decoder.D[i] * _decoder.ai[i];
        _decoder.D[i] = _decoder.D[i - 1];
    }
    _decoder.D[1] = s - rounding(sum, 11);
    return _decoder.D[1];
}

static void interp_lsp(int32_t k) {
    for (int j = 0; j < ORDER; j++) {
        int32_t mul = INTER_WEIGHT[k] * _oldindex[j] + INTER_WEIGHT[k + 4] * _newindex[j];
        int32_t index = mul >> 10;
        int32_t frac = mul & 0x3ff;
        if (index < 0) {
            index = 0;
        } else if (index > 126) {
            index = 126;
        }
        _lsp[j] = COSTABLE[index] + (((COSTABLE[index + 1] - COSTABLE[index]) * frac) >> 10);
    }
}

static void decode_pitch(int32_t p, int32_t k) {
    if ((k & 1) == 0) {
        _pre_p = p;
    } else {
        p += _pre_p - 16;
    }

    if (p <= 191) {
        _period = p / 3 + 13;
        _frac = p % 3;
    } else {
        _period = p - 115;
        _frac = 0;
    }
}

static int32_t sample_pitch(int32_t pos, int32_t f, int32_t s) {
    if (f == 0) {
        if (_pcount < 0) {
            _recursive[s] = _recursive[-_pcount - 1];
        } else {
            _recursive[s] = _ltp[pos];
        }
    } else {
        int32_t fpos = pos + 4;
        if (fpos > 147) {
            fpos -= 148;
        }
        int32_t fcount = _pcount + 5;
        int16_t acc = 0;
        for (int i = 0; i < 8; i++) {
            --fcount;
            if (fcount < 0) {
                acc = (int16_t)(acc + ((_recursive[-fcount - 1] * WSINC[(i << 1) + f - 1]) >> 15));
            } else {
                acc = (int16_t)(acc + ((_ltp[fpos] * WSINC[(i << 1) + f - 1]) >> 15));
                if (fpos > 0) {
                    --fpos;
                } else {
                    fpos += 147;
                }
            }
        }
        _recursive[s] = acc;
    }
    return _recursive[s];
}

static void sub_frame(int32_t subframe_num, int32_t s0, int32_t s1, int32_t fixpos) {
    const int32_t size_fixcodebook = (int32_t)(sizeof(FIXCODEBOOK) / sizeof(FIXCODEBOOK[0]));
    if (fixpos < 0) {
        fixpos = 0;
    } else if (fixpos >= size_fixcodebook) {
        fixpos = size_fixcodebook - 1;
    }

    int32_t code = FIXCODEBOOK[fixpos];
    _inno.Pos[0] = ((code >> 8) & 0xff) * 2 + _inno.Grid;
    _inno.Pos[1] = (code & 0xff) * 2 + _inno.Grid;
    _inno.Sign[0] = (s0 != 0) ? -1 : 1;
    _inno.Sign[1] = (s1 != 0) ? -1 : 1;

    int32_t amp1 = CGAIN[_inno.Gindex] * _inno.Sign[0];
    int32_t amp2 = CGAIN[_inno.Gindex] * _inno.Sign[1];
    decode_pitch(_pitch.Lindex, subframe_num);

    interp_lsp(subframe_num);
    fixlsptopc();

    int32_t base = subframe_num * _sub_frame_size;
    int32_t pitpos = _pm + _period - 1;
    if (pitpos > 147) {
        pitpos -= 148;
    }
    int32_t expos = _ex;
    _pcount = _period - 1;

    for (int32_t i = 0; i < _sub_frame_size; i++, --_pcount) {
        int32_t op = sample_pitch(pitpos, _frac, i);
        op = (PGAIN[_pitch.Gindex] * op) >> 14;
        if (i == _inno.Pos[0]) {
            op += amp1;
            _inno.Pos[0] += _period;
        }
        if (i == _inno.Pos[1]) {
            op += amp2;
            _inno.Pos[1] += _period;
        }
        _ltp[expos] = op;
        _r[i + base] = fixpolefilter(op);
        int32_t t = _r[i + base] * 16;
        _sout[i + base] = (int16_t)(t > 32767 ? 32767 : (t < -32768 ? -32768 : t));

        expos = (expos > 0) ? (expos - 1) : 147;
        pitpos = (pitpos > 0) ? (pitpos - 1) : 147;

        if (i == 1 && subframe_num == 3) {
            for (int j = 0; j < ORDER; j++) {
                _oldindex[j] = _newindex[j];
            }
        }
    }

    _ex -= _sub_frame_size;
    if (_ex < 0) {
        _ex += 148;
    }
    _pm = _ex + 1;
    if (_pm > 147) {
        _pm -= 148;
    }
}

static void dsp_decode_frame(const uint8_t celp[18]) {
    int32_t fixpos;

    _sub_frame_size = ((celp[0] & 0xF0u) == 0x10u) ? 60 : 40;

    _delsp[0] = celp[0] & 0x07;
    _delsp[1] = ((celp[0] & 0x08) >> 3) + ((celp[1] & 0x07) << 1);
    _delsp[2] = (celp[1] & 0x78) >> 3;
    _delsp[3] = ((celp[1] & 0x80) >> 7) + ((celp[2] & 0x07) << 1);
    _delsp[4] = (celp[2] & 0x78) >> 3;
    _delsp[5] = ((celp[2] & 0x80) >> 7) + ((celp[3] & 0x03) << 1);
    _delsp[6] = (celp[3] & 0x1c) >> 2;
    _delsp[7] = (celp[3] & 0xe0) >> 5;
    _delsp[8] = celp[4] & 0x07;
    _delsp[9] = (celp[4] & 0x38) >> 3;

    for (int i = 0; i < ORDER; i++) {
        _newindex[i] = LSPINDEX[_delsp[i] + LSP_SEARCH_PTR[i]];
    }

    if (_lsp_flag) {
        for (int i = 0; i < ORDER; i++) {
            _oldindex[i] = _newindex[i];
        }
        _lsp_flag = false;
    }

    _pitch.Lindex = ((celp[4] & 0xc0) >> 6) + ((celp[5] & 0x3f) << 2);
    fixpos = ((celp[5] & 0xc0) >> 6) + ((celp[6] & 0x7f) << 2);
    _inno.Grid = (celp[6] & 0x80) >> 7;
    _pitch.Gindex = celp[7] & 0x0f;
    _inno.Gindex = (celp[7] & 0xf0) >> 4;
    sub_frame(0, celp[7] & 0x02, celp[7] & 0x01, fixpos);

    _pitch.Lindex = (celp[8] & 0x7c) >> 2;
    fixpos = ((celp[8] & 0x80) >> 7) + ((int32_t)celp[9] << 1);
    _inno.Grid = celp[10] & 0x01;
    _pitch.Gindex = (celp[10] & 0x1e) >> 1;
    _inno.Gindex = ((celp[10] & 0xe0) >> 5) + ((celp[11] & 0x01) << 3);
    sub_frame(1, celp[11] & 0x04, celp[11] & 0x02, fixpos);

    _pitch.Lindex = ((celp[11] & 0xf8) >> 3) + ((celp[12] & 0x07) << 5);
    fixpos = ((celp[12] & 0xf8) >> 3) + ((celp[13] & 0x0f) << 5);
    _inno.Grid = (celp[13] & 0x10) >> 4;
    _pitch.Gindex = ((celp[13] & 0xe0) >> 5) + ((celp[14] & 0x01) << 3);
    _inno.Gindex = (celp[14] & 0x1e) >> 1;
    sub_frame(2, celp[14] & 0x40, celp[14] & 0x20, fixpos);

    _pitch.Lindex = ((celp[14] & 0x80) >> 7) + ((celp[15] & 0x0f) << 1);
    fixpos = ((celp[15] & 0xf0) >> 4) + ((celp[16] & 0x1f) << 4);
    _inno.Grid = (celp[16] & 0x20) >> 5;
    _pitch.Gindex = ((celp[16] & 0xc0) >> 6) + ((celp[17] & 0x03) << 2);
    _inno.Gindex = (celp[17] & 0x3c) >> 2;
    sub_frame(3, celp[17] & 0x80, celp[17] & 0x40, fixpos);
}

static void emit_samples_8000(const int16_t *samples, int32_t count) {
    if (count <= 0) {
        return;
    }
    if (_dsp_mode != WORD_MODE) {
        sound_push_dsp_samples(samples, (size_t)count);
    } else {
        int32_t space = WORD_BUF_MAX - _word_buf_len;
        int32_t to_copy = (count < space) ? count : space;
        if (to_copy > 0) {
            memcpy(&_word_buf[_word_buf_len], samples, (size_t)to_copy * sizeof(int16_t));
            _word_buf_len += to_copy;
        }
    }
}

static void dsp_celp_to_celp(void) {
    _clp_buf[0] = (uint8_t)(((_dsp_celp[0] & 0x8000) != 0 ? 0x20 : 0x10) | (_dsp_celp[0] & 0x0f));

    _clp_buf[1] = (uint8_t)(((_dsp_celp[0] >> 4) & 0x0f) |
                            (((_dsp_celp[0] >> 8) & 0x07) << 4) |
                            ((_dsp_celp[1] & 0x01) << 7));

    _clp_buf[2] = (uint8_t)(((_dsp_celp[1] >> 1) & 0x7f) |
                            (((_dsp_celp[1] >> 8) & 0x01) << 7));

    _clp_buf[3] = (uint8_t)(((_dsp_celp[1] >> 9) & 0x03) |
                            ((_dsp_celp[2] & 0x3f) << 2));

    _clp_buf[4] = (uint8_t)(((_dsp_celp[2] >> 6) & 0x03) |
                            (((_dsp_celp[2] >> 8) & 0x0f) << 2) |
                            ((_dsp_celp[3] & 0x03) << 6));

    _clp_buf[5] = (uint8_t)(((_dsp_celp[3] >> 2) & 0x3f) |
                            ((_dsp_celp[4] & 0x03) << 6));

    _clp_buf[6] = (uint8_t)(((_dsp_celp[4] >> 2) & 0x3f) |
                            (((_dsp_celp[4] >> 8) & 0x03) << 6));

    _clp_buf[7] = (uint8_t)(_dsp_celp[5] & 0xff);

    _clp_buf[8] = (uint8_t)(((_dsp_celp[5] >> 8) & 0x03) |
                            ((_dsp_celp[6] & 0x1f) << 2) |
                            ((_dsp_celp[7] & 0x01) << 7));

    _clp_buf[9] = (uint8_t)(((_dsp_celp[7] >> 1) & 0x7f) |
                            ((_dsp_celp[7] >> 1) & 0x80));

    _clp_buf[10] = (uint8_t)(((_dsp_celp[7] >> 9) & 0x01) |
                             ((_dsp_celp[8] & 0x7f) << 1));

    _clp_buf[11] = (uint8_t)(((_dsp_celp[8] >> 7) & 0x01) |
                             (((_dsp_celp[8] >> 8) & 0x03) << 1) |
                             ((_dsp_celp[9] & 0x1f) << 3));

    _clp_buf[12] = (uint8_t)(((_dsp_celp[9] >> 5) & 0x07) |
                             ((_dsp_celp[10] & 0x1f) << 3));

    _clp_buf[13] = (uint8_t)(((_dsp_celp[10] >> 5) & 0x07) |
                             (((_dsp_celp[10] >> 8) & 0x03) << 3) |
                             ((_dsp_celp[11] & 0x07) << 5));

    _clp_buf[14] = (uint8_t)(((_dsp_celp[11] >> 3) & 0x1f) |
                             (((_dsp_celp[11] >> 8) & 0x03) << 5) |
                             ((_dsp_celp[12] & 0x01) << 7));

    _clp_buf[15] = (uint8_t)(((_dsp_celp[12] >> 1) & 0x0f) |
                             ((_dsp_celp[13] & 0x0f) << 4));

    _clp_buf[16] = (uint8_t)(((_dsp_celp[13] >> 4) & 0x0f) |
                             (((_dsp_celp[13] >> 8) & 0x03) << 4) |
                             ((_dsp_celp[14] & 0x03) << 6));

    _clp_buf[17] = (uint8_t)(((_dsp_celp[14] >> 2) & 0x1f) |
                             (((_dsp_celp[14] >> 8) & 0x03) << 6));

    dsp_decode_frame(_clp_buf);
    int32_t len = (_dsp_celp[0] & 0x8000) != 0 ? 160 : 240;
    emit_samples_8000(_sout, len);
}

static void dsp_write_word(uint8_t high, uint8_t low) {
    if (_dsp_mode == PCM_MODE) {
        if (high == 0xffu) {
            _dsp_mode = CELP_MODE;
        } else {
            int32_t val = (((int32_t)high << 8) | low) - 0x8000;
            int16_t pcm = (int16_t)(val & 0xffff);
            emit_samples_8000(&pcm, 1);
        }
        return;
    }

    if (high < 0x60u) {
        if (_dsp_mode != WORD_MODE && _dsp_mode != CELP_MODE) {
            return;
        }
        _data_cnt++;
        int32_t id = high >> 4;
        if (_dsp_celp_off >= 16) {
            dsp_reset_core();
            return;
        }
        if (id < 3) {
            _dsp_celp_off = id;
            _dsp_celp[_dsp_celp_off++] = ((int32_t)high << 8) | low;
        } else if (id < 6 && (_dsp_celp_off % 3) + 3 == id) {
            _dsp_celp[_dsp_celp_off++] = ((int32_t)high << 8) | low;
            if (_dsp_celp_off == 15) {
                dsp_celp_to_celp();
            }
        }
        return;
    }

    if (high == 0xa0u) {
        if (low != CELP_MODE && low != WORD_MODE && low != PCM_MODE && low != NONE_MODE) {
            dsp_reset_core();
            return;
        }
        _dsp_mode = low;
        _data_cnt = 0;
        return;
    }

    if ((high & 0xf0u) == 0xc0u) {
        if (_dsp_mode != WORD_MODE) {
            return;
        }
        if (high == 0xc2u) {
            _c2 = low;
            _data_cnt = 0;
        } else if (high == 0xc4u) {
            _c4 = low;
            _data_cnt = 0;
        } else if (high == 0xc8u) {
            _c8 = low;
            _data_cnt = 0;
        } else if (high == 0xc3u || high == 0xc1u) {
            if (_data_cnt % 15 != 0) {
                dsp_reset_core();
                return;
            }
            int32_t frames = _data_cnt / 15;
            int32_t end2 = _c4 + 240 * _c8;
            int32_t end3 = (_c4 == 0) ? (240 * frames) : (_c4 + 240 * frames - 240);
            if (end2 != end3) {
                dsp_reset_core();
                return;
            }
            int32_t end = end3;
            if (end > _word_buf_len) {
                end = _word_buf_len;
            }
            for (int32_t j = 0; j + _c2 < end && j < FADE_RANGE; j++) {
                _word_buf[j + _c2] = (int16_t)(((int32_t)_word_buf[j + _c2] * j) / FADE_RANGE);
            }
            for (int32_t j = 0; j < FADE_RANGE && end - 1 - j >= 0; j++) {
                _word_buf[end - 1 - j] = (int16_t)(((int32_t)_word_buf[end - 1 - j] * j) / FADE_RANGE);
            }
            if (_c2 >= 0 && _c2 < end) {
                sound_push_dsp_samples(&_word_buf[_c2], (size_t)(end - _c2));
            }
            _data_cnt = 0;
            _word_buf_len = 0;
            dsp_start();
        }
    }
}

static void dsp_handle_cmd(uint8_t high, uint8_t low) {
    uint16_t cmd = ((uint16_t)high << 8u) | low;
    if (cmd == 0xffffu && _dsp_trans) {
        _dsp_trans = false;
    }
    if (cmd == 0x8000u) {
        _dsp_sleep = true;
    }
    if (_dsp_trans) {
        return;
    }
    if (high == 0xd0u) {
        _dsp_ret_data = 0x5a;
        _dsp_0xd0 = true;
    } else if (high > 0x60u) {
        _dsp_0xd0 = false;
    }

    if (high == 0x70u) {
        if (low == 0x01u || low == 0x02u) {
            _dsp_0x7001_0x7002 = true;
            _dsp_ret_data = 0x06;
        } else {
            _dsp_0x7001_0x7002 = false;
        }
    } else if (high >= 0x60u) {
        _dsp_0x7001_0x7002 = false;
    }

    if (cmd == 0x7004u) {
        _dsp_trans = true;
    }
}

void dsp_init(void) {
    dsp_reset();
}

void dsp_reset(void) {
    memset(_oldindex, 0, sizeof(_oldindex));
    memset(_newindex, 0, sizeof(_newindex));
    memset(_lsp, 0, sizeof(_lsp));
    memset(_r, 0, sizeof(_r));
    memset(_sout, 0, sizeof(_sout));
    memset(_recursive, 0, sizeof(_recursive));
    memset(&_pitch, 0, sizeof(_pitch));
    memset(&_inno, 0, sizeof(_inno));
    memset(&_decoder, 0, sizeof(_decoder));
    _lsp_flag = true;
    _c2 = _c4 = _c8 = 0;

    _dsp_sleep = false;
    _dsp_trans = false;
    _dsp_0xd0 = false;
    _dsp_0x7001_0x7002 = false;
    _dsp_unfetched = false;
    _dsp_ret_data = -1;
    _dsp_data_low = 0;

    dsp_reset_core();
}

void dsp_post_load_states(bool sleeping) {
    dsp_reset();
    _dsp_sleep = sleeping;
}

bool dsp_is_sleeping(void) {
    return _dsp_sleep;
}

uint8_t dsp_read_stat(uint64_t current_cycle) {
    uint8_t value = 0;
    if (_dsp_sleep) {
        value |= DSP_SLEEP_FLAG;
    }
    if (!_dsp_sleep && sound_is_dsp_busy(current_cycle)) {
        if (_dsp_unfetched) {
            value |= DSP_DATA_UNFETCHED_FLAG;
        }
    } else {
        _dsp_unfetched = false;
    }
    if (_dsp_0xd0 || _dsp_0x7001_0x7002 || (_dsp_trans && _dsp_ret_data != -1)) {
        value |= DSP_RETURN_DATA_READY_FLAG;
    }
    return value;
}

uint8_t dsp_read_ret_data(void) {
    if (_dsp_ret_data == -1) {
        return 0xffu;
    }
    uint8_t ret = (uint8_t)_dsp_ret_data;
    _dsp_ret_data = -1;
    return ret;
}

void dsp_write_ctrl(uint8_t value) {
    if ((value & DSP_WAKEUP_CTRL) || (value & DSP_RESET_CTRL)) {
        _dsp_sleep = false;
        dsp_reset_core();
    }
    if (value & DSP_RESET_CTRL) {
        _dsp_ret_data = -1;
        _dsp_unfetched = false;
        _dsp_trans = false;
        _dsp_0x7001_0x7002 = false;
        _dsp_0xd0 = false;
    }
}

void dsp_write_low(uint8_t value) {
    _dsp_data_low = value;
}

void dsp_write_cmd_high(uint8_t value, uint64_t current_cycle) {
    dsp_handle_cmd(value, _dsp_data_low);
    if (_dsp_trans) {
        _dsp_ret_data = _dsp_data_low;
        return;
    }
    if (!_dsp_sleep && sound_is_dsp_busy(current_cycle)) {
        _dsp_unfetched = true;
    }
    if (value == 0xa0u || (value & 0xc0u) || _dsp_mode == PCM_MODE ||
        ((_dsp_mode == CELP_MODE || _dsp_mode == WORD_MODE) && value < 0x60u)) {
        dsp_write_word(value, _dsp_data_low);
    }
}
