/*
 * sidesystem-fido2.c — Hardware-backed virtual FIDO2 / CTAP2 USB HID authenticator
 * (/dev/uhid -> /dev/hidrawN) for SideSystem NixOS containers on Motorola Razr 60 Ultra.
 *
 * Every MakeCredential & GetAssertion generates/uses an unexportable ECDSA P-256 key
 * inside Android StrongBox/TEE KeyStore unlocked by the Razr hardware fingerprint sensor
 * (BiometricAuthActivity via /var/lib/sidesystem/bio-{req,resp}).
 *
 * Zero external library dependencies (pure C + Linux UHID + built-in SHA-256 & CBOR).
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/uhid.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define BRIDGE_SOCK_PATH "/var/lib/sidesystem/bridge.sock"
#define BIO_REQ_PATH     "/var/lib/sidesystem/bio-req"
#define BIO_RESP_PATH    "/var/lib/sidesystem/bio-resp"
#define CRED_DB_PATH     "/var/lib/sidesystem/fido2-creds.db"

/* CTAPHID constants */
#define CTAPHID_BROADCAST_CID 0xffffffffu
#define CTAPHID_TYPE_INIT     0x80u
#define CTAPHID_PING          0x01u
#define CTAPHID_INIT          0x06u
#define CTAPHID_WINK          0x08u
#define CTAPHID_CBOR          0x10u
#define CTAPHID_CANCEL        0x11u
#define CTAPHID_ERROR         0x3fu
#define CTAPHID_KEEPALIVE     0x3bu

#define CTAP2_OK                     0x00u
#define CTAP2_ERR_INVALID_COMMAND    0x01u
#define CTAP2_ERR_INVALID_PARAMETER  0x02u
#define CTAP2_ERR_OPERATION_DENIED   0x27u
#define CTAP2_ERR_NO_CREDENTIALS     0x2eu
#define CTAP2_ERR_KEEPALIVE_CANCEL   0x2du

static const uint8_t FIDO_HID_RDESC[] = {
    0x06, 0xd0, 0xf1, /* Usage Page (FIDO Alliance) */
    0x09, 0x01,       /* Usage (U2F HID Authenticator Device) */
    0xa1, 0x01,       /* Collection (Application) */
    0x09, 0x20,       /*   Usage (Input Report Data) */
    0x15, 0x00,       /*   Logical Minimum (0) */
    0x26, 0xff, 0x00, /*   Logical Maximum (255) */
    0x75, 0x08,       /*   Report Size (8) */
    0x95, 0x40,       /*   Report Count (64) */
    0x81, 0x02,       /*   Input (Data,Var,Abs) */
    0x09, 0x21,       /*   Usage (Output Report Data) */
    0x15, 0x00,       /*   Logical Minimum (0) */
    0x26, 0xff, 0x00, /*   Logical Maximum (255) */
    0x75, 0x08,       /*   Report Size (8) */
    0x95, 0x40,       /*   Report Count (64) */
    0x91, 0x02,       /*   Output (Data,Var,Abs) */
    0xc0              /* End Collection */
};

static const uint8_t AAGUID[16] = {
    'S','i','d','e','S','y','s','t','e','m','R','a','z','r','0','1'
};

static volatile sig_atomic_t g_running = 1;
static int g_uhid_fd = -1;
static uint32_t g_next_cid = 0x01020304u;

static void on_sig(int s) { (void)s; g_running = 0; }

/* ---------------- Minimal SHA-256 ---------------- */
static inline uint32_t rotr32(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

static void sha256(const uint8_t *data, size_t len, uint8_t out[32]) {
    static const uint32_t K[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    };
    uint32_t h[8] = {
        0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
        0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19
    };
    size_t padded = ((len + 9 + 63) / 64) * 64;
    uint8_t *buf = calloc(1, padded);
    memcpy(buf, data, len);
    buf[len] = 0x80;
    uint64_t bits = (uint64_t)len * 8;
    for (int i = 0; i < 8; i++) buf[padded - 1 - i] = (uint8_t)(bits >> (i * 8));

    for (size_t off = 0; off < padded; off += 64) {
        uint32_t w[64];
        for (int i = 0; i < 16; i++) {
            w[i] = ((uint32_t)buf[off + i*4] << 24) | ((uint32_t)buf[off + i*4 + 1] << 16) |
                   ((uint32_t)buf[off + i*4 + 2] << 8) | buf[off + i*4 + 3];
        }
        for (int i = 16; i < 64; i++) {
            uint32_t s0 = rotr32(w[i-15], 7) ^ rotr32(w[i-15], 18) ^ (w[i-15] >> 3);
            uint32_t s1 = rotr32(w[i-2], 17) ^ rotr32(w[i-2], 19) ^ (w[i-2] >> 10);
            w[i] = w[i-16] + s0 + w[i-7] + s1;
        }
        uint32_t a=h[0], b=h[1], c=h[2], d=h[3], e=h[4], f=h[5], g=h[6], hh=h[7];
        for (int i = 0; i < 64; i++) {
            uint32_t S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
            uint32_t ch = (e & f) ^ ((~e) & g);
            uint32_t t1 = hh + S1 + ch + K[i] + w[i];
            uint32_t S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
            uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            uint32_t t2 = S0 + maj;
            hh=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
        }
        h[0]+=a; h[1]+=b; h[2]+=c; h[3]+=d; h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=hh;
    }
    free(buf);
    for (int i = 0; i < 8; i++) {
        out[i*4]   = (uint8_t)(h[i] >> 24);
        out[i*4+1] = (uint8_t)(h[i] >> 16);
        out[i*4+2] = (uint8_t)(h[i] >> 8);
        out[i*4+3] = (uint8_t)(h[i]);
    }
}

/* ---------------- Hex & Base64 helpers ---------------- */
static void to_hex(const uint8_t *in, size_t len, char *out) {
    static const char H[] = "0123456789abcdef";
    for (size_t i = 0; i < len; i++) {
        out[i*2]   = H[in[i] >> 4];
        out[i*2+1] = H[in[i] & 0xf];
    }
    out[len*2] = '\0';
}

static int hex_val(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static size_t from_hex(const char *hex, uint8_t *out, size_t max_out) {
    size_t len = strlen(hex) / 2;
    if (len > max_out) len = max_out;
    for (size_t i = 0; i < len; i++) {
        int hi = hex_val(hex[i*2]), lo = hex_val(hex[i*2+1]);
        if (hi < 0 || lo < 0) return i;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    return len;
}

static size_t b64_encode(const uint8_t *in, size_t len, char *out) {
    static const char T[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t j = 0;
    for (size_t i = 0; i < len; i += 3) {
        uint32_t v = ((uint32_t)in[i] << 16) |
                     (i + 1 < len ? ((uint32_t)in[i+1] << 8) : 0) |
                     (i + 2 < len ? (uint32_t)in[i+2] : 0);
        out[j++] = T[(v >> 18) & 63];
        out[j++] = T[(v >> 12) & 63];
        out[j++] = (i + 1 < len) ? T[(v >> 6) & 63] : '=';
        out[j++] = (i + 2 < len) ? T[v & 63] : '=';
    }
    out[j] = '\0';
    return j;
}

static int b64_idx(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static size_t b64_decode(const char *in, uint8_t *out, size_t max_out) {
    size_t len = strlen(in), j = 0;
    uint32_t buf = 0;
    int bits = 0;
    for (size_t i = 0; i < len; i++) {
        int v = b64_idx(in[i]);
        if (v < 0) continue;
        buf = (buf << 6) | (uint32_t)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (j < max_out) out[j++] = (uint8_t)((buf >> bits) & 0xff);
        }
    }
    return j;
}

static bool get_random_bytes(uint8_t *buf, size_t len) {
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) return false;
    ssize_t n = read(fd, buf, len);
    close(fd);
    return n == (ssize_t)len;
}

/* ---------------- Minimal CBOR writer ---------------- */
typedef struct {
    uint8_t *buf;
    size_t len;
    size_t cap;
} CborBuf;

static void cb_put(CborBuf *b, uint8_t v) {
    if (b->len < b->cap) b->buf[b->len++] = v;
}
static void cb_put_bytes(CborBuf *b, const void *p, size_t n) {
    if (b->len + n <= b->cap) {
        memcpy(b->buf + b->len, p, n);
        b->len += n;
    }
}
static void cb_head(CborBuf *b, uint8_t major, uint64_t val) {
    uint8_t m = (uint8_t)(major << 5);
    if (val < 24) {
        cb_put(b, (uint8_t)(m | val));
    } else if (val < 256) {
        cb_put(b, (uint8_t)(m | 24));
        cb_put(b, (uint8_t)val);
    } else if (val < 65536) {
        cb_put(b, (uint8_t)(m | 25));
        cb_put(b, (uint8_t)(val >> 8));
        cb_put(b, (uint8_t)val);
    } else {
        cb_put(b, (uint8_t)(m | 26));
        cb_put(b, (uint8_t)(val >> 24));
        cb_put(b, (uint8_t)(val >> 16));
        cb_put(b, (uint8_t)(val >> 8));
        cb_put(b, (uint8_t)val);
    }
}
static void cb_uint(CborBuf *b, uint64_t v) { cb_head(b, 0, v); }
static void cb_nint(CborBuf *b, int64_t v)  { cb_head(b, 1, (uint64_t)(-1 - v)); }
static void cb_bstr(CborBuf *b, const uint8_t *p, size_t n) { cb_head(b, 2, n); cb_put_bytes(b, p, n); }
static void cb_tstr(CborBuf *b, const char *s) { size_t n = strlen(s); cb_head(b, 3, n); cb_put_bytes(b, s, n); }
static void cb_arr(CborBuf *b, size_t n) { cb_head(b, 4, n); }
static void cb_map(CborBuf *b, size_t n) { cb_head(b, 5, n); }
static void cb_bool(CborBuf *b, bool v)  { cb_put(b, v ? 0xf5 : 0xf4); }

/* ---------------- Minimal CBOR reader ---------------- */
typedef struct {
    const uint8_t *buf;
    size_t len;
    size_t pos;
} CborR;

static bool cr_head(CborR *r, uint8_t *major, uint64_t *val) {
    if (r->pos >= r->len) return false;
    uint8_t b = r->buf[r->pos++];
    *major = b >> 5;
    uint8_t ai = b & 0x1f;
    if (ai < 24) { *val = ai; return true; }
    if (ai == 24) {
        if (r->pos + 1 > r->len) return false;
        *val = r->buf[r->pos++]; return true;
    }
    if (ai == 25) {
        if (r->pos + 2 > r->len) return false;
        *val = ((uint64_t)r->buf[r->pos] << 8) | r->buf[r->pos+1];
        r->pos += 2; return true;
    }
    if (ai == 26) {
        if (r->pos + 4 > r->len) return false;
        *val = ((uint64_t)r->buf[r->pos] << 24) | ((uint64_t)r->buf[r->pos+1] << 16) |
               ((uint64_t)r->buf[r->pos+2] << 8) | r->buf[r->pos+3];
        r->pos += 4; return true;
    }
    if (ai == 27) {
        if (r->pos + 8 > r->len) return false;
        uint64_t v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | r->buf[r->pos++];
        *val = v; return true;
    }
    return false;
}

static bool cr_skip(CborR *r) {
    uint8_t maj; uint64_t val;
    if (!cr_head(r, &maj, &val)) return false;
    switch (maj) {
        case 0: case 1: case 7: return true;
        case 2: case 3:
            if (r->pos + val > r->len) return false;
            r->pos += (size_t)val; return true;
        case 4:
            for (uint64_t i = 0; i < val; i++) if (!cr_skip(r)) return false;
            return true;
        case 5:
            for (uint64_t i = 0; i < val; i++) {
                if (!cr_skip(r) || !cr_skip(r)) return false;
            }
            return true;
        case 6: return cr_skip(r);
        default: return false;
    }
}

/* ---------------- Credential DB (/var/lib/sidesystem/fido2-creds.db) ---------------- */
typedef struct {
    char cred_id_hex[129];
    char rp_id[256];
    char user_id_hex[129];
    char user_name[128];
    char pub_x_hex[65];
    char pub_y_hex[65];
    uint32_t sign_count;
} CredRec;

static void sanitize_field(char *s) {
    for (; *s; s++) if (*s == '\t' || *s == '\n' || *s == '\r' || *s == '|') *s = '_';
}

static void save_cred(const CredRec *rec) {
    FILE *f = fopen(CRED_DB_PATH, "a");
    if (!f) return;
    fprintf(f, "%s\t%s\t%s\t%s\t%s\t%s\t%u\n",
            rec->cred_id_hex, rec->rp_id, rec->user_id_hex,
            rec->user_name, rec->pub_x_hex, rec->pub_y_hex, rec->sign_count);
    fclose(f);
    chmod(CRED_DB_PATH, 0600);
}

static bool find_cred(const char *rp_id, const char allow_hex[][129], size_t allow_cnt, CredRec *out) {
    FILE *f = fopen(CRED_DB_PATH, "r");
    if (!f) return false;
    char line[1024];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        CredRec r;
        memset(&r, 0, sizeof(r));
        if (sscanf(line, "%128[^\t]\t%255[^\t]\t%128[^\t]\t%127[^\t]\t%64[^\t]\t%64[^\t]\t%u",
                   r.cred_id_hex, r.rp_id, r.user_id_hex, r.user_name,
                   r.pub_x_hex, r.pub_y_hex, &r.sign_count) == 7) {
            if (strcmp(r.rp_id, rp_id) != 0) continue;
            if (allow_cnt > 0) {
                for (size_t i = 0; i < allow_cnt; i++) {
                    if (strcasecmp(r.cred_id_hex, allow_hex[i]) == 0) {
                        *out = r;
                        found = true;
                        break;
                    }
                }
                if (found) break;
            } else {
                *out = r;
                found = true;
            }
        }
    }
    fclose(f);
    return found;
}

static void bump_sign_count(const char *cred_id_hex, uint32_t new_count) {
    FILE *in = fopen(CRED_DB_PATH, "r");
    if (!in) return;
    FILE *out = fopen(CRED_DB_PATH ".tmp", "w");
    if (!out) { fclose(in); return; }
    char line[1024];
    while (fgets(line, sizeof(line), in)) {
        CredRec r;
        memset(&r, 0, sizeof(r));
        if (sscanf(line, "%128[^\t]\t%255[^\t]\t%128[^\t]\t%127[^\t]\t%64[^\t]\t%64[^\t]\t%u",
                   r.cred_id_hex, r.rp_id, r.user_id_hex, r.user_name,
                   r.pub_x_hex, r.pub_y_hex, &r.sign_count) == 7) {
            if (strcasecmp(r.cred_id_hex, cred_id_hex) == 0) r.sign_count = new_count;
            fprintf(out, "%s\t%s\t%s\t%s\t%s\t%s\t%u\n",
                    r.cred_id_hex, r.rp_id, r.user_id_hex,
                    r.user_name, r.pub_x_hex, r.pub_y_hex, r.sign_count);
        }
    }
    fclose(in);
    fclose(out);
    chmod(CRED_DB_PATH ".tmp", 0600);
    rename(CRED_DB_PATH ".tmp", CRED_DB_PATH);
}

/* ---------------- UHID send helpers ---------------- */
static void uhid_send_report(const uint8_t pkt[64]) {
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_INPUT2;
    ev.u.input2.size = 64;
    memcpy(ev.u.input2.data, pkt, 64);
    (void)write(g_uhid_fd, &ev, sizeof(ev));
}

static void ctaphid_send_msg(uint32_t cid, uint8_t cmd, const uint8_t *data, size_t len) {
    uint8_t pkt[64];
    memset(pkt, 0, sizeof(pkt));
    pkt[0] = (uint8_t)(cid >> 24);
    pkt[1] = (uint8_t)(cid >> 16);
    pkt[2] = (uint8_t)(cid >> 8);
    pkt[3] = (uint8_t)(cid);
    pkt[4] = (uint8_t)(CTAPHID_TYPE_INIT | cmd);
    pkt[5] = (uint8_t)(len >> 8);
    pkt[6] = (uint8_t)(len & 0xff);
    size_t chunk = len < 57 ? len : 57;
    if (chunk) memcpy(pkt + 7, data, chunk);
    uhid_send_report(pkt);

    size_t off = chunk;
    uint8_t seq = 0;
    while (off < len) {
        memset(pkt, 0, sizeof(pkt));
        pkt[0] = (uint8_t)(cid >> 24);
        pkt[1] = (uint8_t)(cid >> 16);
        pkt[2] = (uint8_t)(cid >> 8);
        pkt[3] = (uint8_t)(cid);
        pkt[4] = seq++;
        size_t n = (len - off) < 59 ? (len - off) : 59;
        memcpy(pkt + 5, data + off, n);
        uhid_send_report(pkt);
        off += n;
    }
}

static void ctaphid_send_keepalive(uint32_t cid, uint8_t status) {
    ctaphid_send_msg(cid, CTAPHID_KEEPALIVE, &status, 1);
}

/* ---------------- Android StrongBox Biometric RPC with CTAPHID Keepalive ---------------- */
static bool call_android_fido2(uint32_t cid, const char *req_line, char *resp_out, size_t resp_sz) {
    /* Fast path: event-driven Unix socket to sidesystem-bridged (<10ms latency) */
    int sfd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sfd >= 0) {
        struct sockaddr_un addr;
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", BRIDGE_SOCK_PATH);
        if (connect(sfd, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
            char msg[2048];
            int mlen = snprintf(msg, sizeof(msg), "BIO_REQ %s\n", req_line);
            if (mlen > 0 && send(sfd, msg, (size_t)mlen, MSG_NOSIGNAL) == mlen) {
                for (int i = 0; i < 300 && g_running; i++) {
                    ctaphid_send_keepalive(cid, 2);
                    struct pollfd pfd = { .fd = sfd, .events = POLLIN, .revents = 0 };
                    int rc = poll(&pfd, 1, 100);
                    if (rc > 0 && (pfd.revents & (POLLIN | POLLHUP))) {
                        ssize_t r = recv(sfd, resp_out, resp_sz - 1, 0);
                        close(sfd);
                        if (r > 0) {
                            resp_out[r] = '\0';
                            while (r > 0 && (resp_out[r - 1] == '\n' || resp_out[r - 1] == '\r'))
                                resp_out[--r] = '\0';
                            return resp_out[0] != '\0';
                        }
                        return false;
                    }
                }
            }
        }
        close(sfd);
    }

    /* Fallback path: legacy file-based bio-req / bio-resp */
    FILE *tf = fopen(BIO_RESP_PATH, "w");
    if (tf) fclose(tf);

    FILE *rf = fopen(BIO_REQ_PATH, "w");
    if (!rf) return false;
    fprintf(rf, "%s\n", req_line);
    fclose(rf);

    /* Wait up to 30s, sending CTAPHID_KEEPALIVE(2 = UPNEEDED) every 100ms */
    for (int i = 0; i < 300 && g_running; i++) {
        ctaphid_send_keepalive(cid, 2);
        usleep(100000);
        FILE *f = fopen(BIO_RESP_PATH, "r");
        if (f) {
            if (fgets(resp_out, (int)resp_sz, f) && resp_out[0] != '\0' && resp_out[0] != '\n') {
                fclose(f);
                FILE *cf = fopen(BIO_RESP_PATH, "w");
                if (cf) fclose(cf);
                size_t l = strlen(resp_out);
                while (l > 0 && (resp_out[l-1] == '\n' || resp_out[l-1] == '\r')) resp_out[--l] = '\0';
                return true;
            }
            fclose(f);
        }
    }
    return false;
}

/* ---------------- CTAP2 Handlers ---------------- */
static void handle_get_info(uint32_t cid) {
    uint8_t out[256];
    out[0] = CTAP2_OK;
    CborBuf cb = { out + 1, 0, sizeof(out) - 1 };

    cb_map(&cb, 4);
    /* 1: versions */
    cb_uint(&cb, 1);
    cb_arr(&cb, 2);
    cb_tstr(&cb, "FIDO_2_0");
    cb_tstr(&cb, "FIDO_2_1_PRE");
    /* 3: aaguid */
    cb_uint(&cb, 3);
    cb_bstr(&cb, AAGUID, 16);
    /* 4: options (canonical CBOR key order: shorter length first, then lex) */
    cb_uint(&cb, 4);
    cb_map(&cb, 4);
    cb_tstr(&cb, "rk");   cb_bool(&cb, true);
    cb_tstr(&cb, "up");   cb_bool(&cb, true);
    cb_tstr(&cb, "uv");   cb_bool(&cb, true);
    cb_tstr(&cb, "plat"); cb_bool(&cb, false);
    /* 5: maxMsgSize */
    cb_uint(&cb, 5);
    cb_uint(&cb, 1200);

    ctaphid_send_msg(cid, CTAPHID_CBOR, out, 1 + cb.len);
}

static void handle_make_credential(uint32_t cid, const uint8_t *cbor, size_t cbor_len) {
    CborR r = { cbor, cbor_len, 0 };
    uint8_t maj; uint64_t pairs;
    if (!cr_head(&r, &maj, &pairs) || maj != 5) {
        uint8_t err = CTAP2_ERR_INVALID_PARAMETER;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    char rp_id[256] = {0};
    uint8_t user_id[64] = {0}; size_t user_id_len = 0;
    char user_name[128] = "thomas";

    for (uint64_t i = 0; i < pairs; i++) {
        uint8_t kmaj; uint64_t key;
        if (!cr_head(&r, &kmaj, &key) || kmaj != 0) { cr_skip(&r); continue; }
        if (key == 2) { /* rp map */
            uint8_t mm; uint64_t mp;
            if (cr_head(&r, &mm, &mp) && mm == 5) {
                for (uint64_t j = 0; j < mp; j++) {
                    uint8_t fkm; uint64_t fkl;
                    if (!cr_head(&r, &fkm, &fkl) || fkm != 3 || r.pos + fkl > r.len) break;
                    const char *fk = (const char *)(r.buf + r.pos); r.pos += (size_t)fkl;
                    if (fkl == 2 && memcmp(fk, "id", 2) == 0) {
                        uint8_t vm; uint64_t vl;
                        if (cr_head(&r, &vm, &vl) && vm == 3 && r.pos + vl <= r.len) {
                            size_t cp = vl < sizeof(rp_id) - 1 ? (size_t)vl : sizeof(rp_id) - 1;
                            memcpy(rp_id, r.buf + r.pos, cp); rp_id[cp] = '\0';
                            r.pos += (size_t)vl;
                        }
                    } else { cr_skip(&r); }
                }
            }
        } else if (key == 3) { /* user map */
            uint8_t mm; uint64_t mp;
            if (cr_head(&r, &mm, &mp) && mm == 5) {
                for (uint64_t j = 0; j < mp; j++) {
                    uint8_t fkm; uint64_t fkl;
                    if (!cr_head(&r, &fkm, &fkl) || fkm != 3 || r.pos + fkl > r.len) break;
                    const char *fk = (const char *)(r.buf + r.pos); r.pos += (size_t)fkl;
                    if (fkl == 2 && memcmp(fk, "id", 2) == 0) {
                        uint8_t vm; uint64_t vl;
                        if (cr_head(&r, &vm, &vl) && vm == 2 && r.pos + vl <= r.len) {
                            user_id_len = vl < sizeof(user_id) ? (size_t)vl : sizeof(user_id);
                            memcpy(user_id, r.buf + r.pos, user_id_len);
                            r.pos += (size_t)vl;
                        }
                    } else if (fkl == 4 && memcmp(fk, "name", 4) == 0) {
                        uint8_t vm; uint64_t vl;
                        if (cr_head(&r, &vm, &vl) && vm == 3 && r.pos + vl <= r.len) {
                            size_t cp = vl < sizeof(user_name) - 1 ? (size_t)vl : sizeof(user_name) - 1;
                            memcpy(user_name, r.buf + r.pos, cp); user_name[cp] = '\0';
                            r.pos += (size_t)vl;
                        }
                    } else { cr_skip(&r); }
                }
            }
        } else {
            cr_skip(&r);
        }
    }

    if (!rp_id[0]) {
        uint8_t err = CTAP2_ERR_INVALID_PARAMETER;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    uint8_t cred_id[32];
    if (!get_random_bytes(cred_id, sizeof(cred_id))) {
        uint8_t err = CTAP2_ERR_OPERATION_DENIED;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }
    char cred_hex[65];
    to_hex(cred_id, sizeof(cred_id), cred_hex);
    sanitize_field(rp_id);
    sanitize_field(user_name);

    char req[512], resp[512] = {0};
    snprintf(req, sizeof(req), "FIDO2_MAKE|%s|%s", cred_hex, rp_id);
    if (!call_android_fido2(cid, req, resp, sizeof(resp))) {
        uint8_t err = CTAP2_ERR_OPERATION_DENIED;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    char tag[32] = {0}, rcred[129] = {0}, x_hex[65] = {0}, y_hex[65] = {0};
    if (sscanf(resp, "%31s %128s %64s %64s", tag, rcred, x_hex, y_hex) != 4 ||
        strcmp(tag, "OK_FIDO2") != 0 || strcmp(rcred, cred_hex) != 0) {
        uint8_t err = CTAP2_ERR_OPERATION_DENIED;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    uint8_t pub_x[32], pub_y[32];
    if (from_hex(x_hex, pub_x, 32) != 32 || from_hex(y_hex, pub_y, 32) != 32) {
        uint8_t err = CTAP2_ERR_OPERATION_DENIED;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    /* Save non-secret metadata */
    CredRec rec;
    memset(&rec, 0, sizeof(rec));
    snprintf(rec.cred_id_hex, sizeof(rec.cred_id_hex), "%s", cred_hex);
    snprintf(rec.rp_id, sizeof(rec.rp_id), "%s", rp_id);
    to_hex(user_id, user_id_len, rec.user_id_hex);
    if (!rec.user_id_hex[0]) snprintf(rec.user_id_hex, sizeof(rec.user_id_hex), "00");
    snprintf(rec.user_name, sizeof(rec.user_name), "%s", user_name[0] ? user_name : "thomas");
    snprintf(rec.pub_x_hex, sizeof(rec.pub_x_hex), "%s", x_hex);
    snprintf(rec.pub_y_hex, sizeof(rec.pub_y_hex), "%s", y_hex);
    rec.sign_count = 1;
    save_cred(&rec);

    /* Build COSE_Key (ES256 P-256) */
    uint8_t cose[128];
    CborBuf ck = { cose, 0, sizeof(cose) };
    cb_map(&ck, 5);
    cb_uint(&ck, 1);  cb_uint(&ck, 2);     /* kty: EC2 */
    cb_uint(&ck, 3);  cb_nint(&ck, -7);    /* alg: ES256 */
    cb_nint(&ck, -1); cb_uint(&ck, 1);     /* crv: P-256 */
    cb_nint(&ck, -2); cb_bstr(&ck, pub_x, 32);
    cb_nint(&ck, -3); cb_bstr(&ck, pub_y, 32);

    /* Build authData: rpIdHash(32) | flags(1=0x45 UP|UV|AT) | signCount(4) | aaguid(16) | credIdLen(2) | credId(32) | cose */
    uint8_t auth_data[256];
    size_t ad_len = 0;
    sha256((const uint8_t *)rp_id, strlen(rp_id), auth_data);
    ad_len += 32;
    auth_data[ad_len++] = 0x45; /* UP | UV | AT */
    auth_data[ad_len++] = 0; auth_data[ad_len++] = 0; auth_data[ad_len++] = 0; auth_data[ad_len++] = 1;
    memcpy(auth_data + ad_len, AAGUID, 16); ad_len += 16;
    auth_data[ad_len++] = 0; auth_data[ad_len++] = 32;
    memcpy(auth_data + ad_len, cred_id, 32); ad_len += 32;
    memcpy(auth_data + ad_len, cose, ck.len); ad_len += ck.len;

    /* CTAP2 response: { 1: "none", 2: authData, 3: {} } */
    uint8_t out[512];
    out[0] = CTAP2_OK;
    CborBuf cb = { out + 1, 0, sizeof(out) - 1 };
    cb_map(&cb, 3);
    cb_uint(&cb, 1); cb_tstr(&cb, "none");
    cb_uint(&cb, 2); cb_bstr(&cb, auth_data, ad_len);
    cb_uint(&cb, 3); cb_map(&cb, 0);

    ctaphid_send_msg(cid, CTAPHID_CBOR, out, 1 + cb.len);
}

static void handle_get_assertion(uint32_t cid, const uint8_t *cbor, size_t cbor_len) {
    CborR r = { cbor, cbor_len, 0 };
    uint8_t maj; uint64_t pairs;
    if (!cr_head(&r, &maj, &pairs) || maj != 5) {
        uint8_t err = CTAP2_ERR_INVALID_PARAMETER;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    char rp_id[256] = {0};
    uint8_t cdh[32] = {0}; bool has_cdh = false;
    char allow_hex[16][129]; size_t allow_cnt = 0;

    for (uint64_t i = 0; i < pairs; i++) {
        uint8_t kmaj; uint64_t key;
        if (!cr_head(&r, &kmaj, &key) || kmaj != 0) { cr_skip(&r); continue; }
        if (key == 1) { /* rpId */
            uint8_t vm; uint64_t vl;
            if (cr_head(&r, &vm, &vl) && vm == 3 && r.pos + vl <= r.len) {
                size_t cp = vl < sizeof(rp_id) - 1 ? (size_t)vl : sizeof(rp_id) - 1;
                memcpy(rp_id, r.buf + r.pos, cp); rp_id[cp] = '\0';
                r.pos += (size_t)vl;
            }
        } else if (key == 2) { /* clientDataHash */
            uint8_t vm; uint64_t vl;
            if (cr_head(&r, &vm, &vl) && vm == 2 && vl == 32 && r.pos + 32 <= r.len) {
                memcpy(cdh, r.buf + r.pos, 32); has_cdh = true;
                r.pos += 32;
            }
        } else if (key == 3) { /* allowList */
            uint8_t am; uint64_t alen;
            if (cr_head(&r, &am, &alen) && am == 4) {
                for (uint64_t j = 0; j < alen; j++) {
                    uint8_t mm; uint64_t mp;
                    if (cr_head(&r, &mm, &mp) && mm == 5) {
                        for (uint64_t k = 0; k < mp; k++) {
                            uint8_t fkm; uint64_t fkl;
                            if (!cr_head(&r, &fkm, &fkl) || fkm != 3 || r.pos + fkl > r.len) break;
                            const char *fk = (const char *)(r.buf + r.pos); r.pos += (size_t)fkl;
                            if (fkl == 2 && memcmp(fk, "id", 2) == 0) {
                                uint8_t vm; uint64_t vl;
                                if (cr_head(&r, &vm, &vl) && vm == 2 && r.pos + vl <= r.len) {
                                    if (allow_cnt < 16 && vl <= 64) {
                                        to_hex(r.buf + r.pos, (size_t)vl, allow_hex[allow_cnt++]);
                                    }
                                    r.pos += (size_t)vl;
                                }
                            } else { cr_skip(&r); }
                        }
                    }
                }
            }
        } else {
            cr_skip(&r);
        }
    }

    if (!rp_id[0] || !has_cdh) {
        uint8_t err = CTAP2_ERR_INVALID_PARAMETER;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    CredRec rec;
    if (!find_cred(rp_id, allow_hex, allow_cnt, &rec)) {
        uint8_t err = CTAP2_ERR_NO_CREDENTIALS;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    uint32_t new_cnt = rec.sign_count + 1;
    /* authData: rpIdHash(32) | flags(1=0x05 UP|UV) | signCount(4) */
    uint8_t auth_data[37];
    sha256((const uint8_t *)rp_id, strlen(rp_id), auth_data);
    auth_data[32] = 0x05; /* UP | UV */
    auth_data[33] = (uint8_t)(new_cnt >> 24);
    auth_data[34] = (uint8_t)(new_cnt >> 16);
    auth_data[35] = (uint8_t)(new_cnt >> 8);
    auth_data[36] = (uint8_t)(new_cnt);

    uint8_t to_sign[37 + 32];
    memcpy(to_sign, auth_data, 37);
    memcpy(to_sign + 37, cdh, 32);
    char payload_b64[128];
    b64_encode(to_sign, sizeof(to_sign), payload_b64);

    char req[768], resp[512] = {0};
    snprintf(req, sizeof(req), "FIDO2_GET|%s|%s|%s", rec.cred_id_hex, rp_id, payload_b64);
    if (!call_android_fido2(cid, req, resp, sizeof(resp))) {
        uint8_t err = CTAP2_ERR_OPERATION_DENIED;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    char tag[32] = {0}, rcred[129] = {0}, sig_b64[256] = {0};
    if (sscanf(resp, "%31s %128s %255s", tag, rcred, sig_b64) != 3 ||
        strcmp(tag, "OK_FIDO2") != 0 || strcasecmp(rcred, rec.cred_id_hex) != 0) {
        uint8_t err = CTAP2_ERR_OPERATION_DENIED;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    uint8_t sig_der[128];
    size_t sig_len = b64_decode(sig_b64, sig_der, sizeof(sig_der));
    if (sig_len < 8) {
        uint8_t err = CTAP2_ERR_OPERATION_DENIED;
        ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        return;
    }

    bump_sign_count(rec.cred_id_hex, new_cnt);

    uint8_t cred_id[64], user_id[64];
    size_t cred_len = from_hex(rec.cred_id_hex, cred_id, sizeof(cred_id));
    size_t uid_len  = from_hex(rec.user_id_hex, user_id, sizeof(user_id));

    /* CTAP2 response: { 1: {id, type}, 2: authData, 3: signature, 4: {id, name} } */
    uint8_t out[512];
    out[0] = CTAP2_OK;
    CborBuf cb = { out + 1, 0, sizeof(out) - 1 };
    cb_map(&cb, 4);
    cb_uint(&cb, 1);
    cb_map(&cb, 2);
    cb_tstr(&cb, "id");   cb_bstr(&cb, cred_id, cred_len);
    cb_tstr(&cb, "type"); cb_tstr(&cb, "public-key");
    cb_uint(&cb, 2); cb_bstr(&cb, auth_data, sizeof(auth_data));
    cb_uint(&cb, 3); cb_bstr(&cb, sig_der, sig_len);
    cb_uint(&cb, 4);
    cb_map(&cb, 2);
    cb_tstr(&cb, "id");   cb_bstr(&cb, user_id, uid_len);
    cb_tstr(&cb, "name"); cb_tstr(&cb, rec.user_name);

    ctaphid_send_msg(cid, CTAPHID_CBOR, out, 1 + cb.len);
}

static void dispatch_ctaphid(uint32_t cid, uint8_t cmd, const uint8_t *data, size_t len) {
    if (cmd == CTAPHID_INIT && len >= 8) {
        uint32_t new_cid = (cid == CTAPHID_BROADCAST_CID) ? g_next_cid++ : cid;
        uint8_t resp[17];
        memcpy(resp, data, 8);
        resp[8]  = (uint8_t)(new_cid >> 24);
        resp[9]  = (uint8_t)(new_cid >> 16);
        resp[10] = (uint8_t)(new_cid >> 8);
        resp[11] = (uint8_t)(new_cid);
        resp[12] = 2;    /* CTAPHID protocol version */
        resp[13] = 1;    /* major */
        resp[14] = 0;    /* minor */
        resp[15] = 0;    /* build */
        resp[16] = 0x04; /* CAPABILITY_CBOR */
        ctaphid_send_msg(cid, CTAPHID_INIT, resp, sizeof(resp));
    } else if (cmd == CTAPHID_PING) {
        ctaphid_send_msg(cid, CTAPHID_PING, data, len);
    } else if (cmd == CTAPHID_WINK) {
        ctaphid_send_msg(cid, CTAPHID_WINK, NULL, 0);
    } else if (cmd == CTAPHID_CBOR && len >= 1) {
        uint8_t ctap_cmd = data[0];
        if (ctap_cmd == 0x04) {
            handle_get_info(cid);
        } else if (ctap_cmd == 0x01) {
            handle_make_credential(cid, data + 1, len - 1);
        } else if (ctap_cmd == 0x02) {
            handle_get_assertion(cid, data + 1, len - 1);
        } else {
            uint8_t err = CTAP2_ERR_INVALID_COMMAND;
            ctaphid_send_msg(cid, CTAPHID_CBOR, &err, 1);
        }
    } else {
        uint8_t err = 0x01; /* ERR_INVALID_CMD */
        ctaphid_send_msg(cid, CTAPHID_ERROR, &err, 1);
    }
}

/* ---------------- Auto-expose /dev/hidrawN inside droidspaces container ---------------- */
static void expose_hidraw_node(void) {
    for (int attempt = 0; attempt < 20; attempt++) {
        DIR *d = opendir("/sys/class/hidraw");
        if (d) {
            struct dirent *ent;
            while ((ent = readdir(d)) != NULL) {
                if (strncmp(ent->d_name, "hidraw", 6) != 0) continue;
                char uev[256], devf[256], line[256];
                snprintf(uev, sizeof(uev), "/sys/class/hidraw/%s/device/uevent", ent->d_name);
                FILE *f = fopen(uev, "r");
                bool match = false;
                if (f) {
                    while (fgets(line, sizeof(line), f)) {
                        if (strstr(line, "SideSystem Razr Touch ID")) { match = true; break; }
                    }
                    fclose(f);
                }
                if (!match) continue;

                snprintf(devf, sizeof(devf), "/sys/class/hidraw/%s/dev", ent->d_name);
                FILE *df = fopen(devf, "r");
                if (df) {
                    unsigned maj = 0, min = 0;
                    if (fscanf(df, "%u:%u", &maj, &min) == 2) {
                        char node[64];
                        snprintf(node, sizeof(node), "/dev/%s", ent->d_name);
                        unlink(node);
                        mknod(node, S_IFCHR | 0666, makedev(maj, min));
                        chmod(node, 0666);
                        fprintf(stderr, "sidesystem-fido2: exposed %s (%u:%u)\n", node, maj, min);
                    }
                    fclose(df);
                }
                closedir(d);
                return;
            }
            closedir(d);
        }
        usleep(100000);
    }
}

int main(void) {
    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);

    /* Ensure /dev/uhid exists (char 10:239) */
    if (access("/dev/uhid", F_OK) != 0) {
        mknod("/dev/uhid", S_IFCHR | 0660, makedev(10, 239));
    }

    g_uhid_fd = open("/dev/uhid", O_RDWR | O_CLOEXEC);
    if (g_uhid_fd < 0) {
        perror("sidesystem-fido2: open /dev/uhid");
        return 1;
    }

    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_CREATE2;
    snprintf((char *)ev.u.create2.name, sizeof(ev.u.create2.name), "SideSystem Razr Touch ID");
    memcpy(ev.u.create2.rd_data, FIDO_HID_RDESC, sizeof(FIDO_HID_RDESC));
    ev.u.create2.rd_size = sizeof(FIDO_HID_RDESC);
    ev.u.create2.bus = BUS_USB;
    ev.u.create2.vendor = 0x1209;
    ev.u.create2.product = 0x51de;
    ev.u.create2.version = 1;
    ev.u.create2.country = 0;

    if (write(g_uhid_fd, &ev, sizeof(ev)) < 0) {
        perror("sidesystem-fido2: UHID_CREATE2");
        close(g_uhid_fd);
        return 1;
    }

    expose_hidraw_node();

    /* CTAPHID reassembly buffer */
    uint32_t cur_cid = 0;
    uint8_t cur_cmd = 0;
    uint16_t cur_total = 0;
    size_t cur_got = 0;
    uint8_t cur_buf[4096];

    struct pollfd pfd = { .fd = g_uhid_fd, .events = POLLIN };
    while (g_running) {
        int pr = poll(&pfd, 1, -1);
        if (pr <= 0) continue;

        struct uhid_event in;
        ssize_t n = read(g_uhid_fd, &in, sizeof(in));
        if (n < (ssize_t)sizeof(in.type)) continue;

        if (in.type == UHID_OUTPUT) {
            const uint8_t *d = in.u.output.data;
            size_t sz = in.u.output.size;
            /* Strip optional leading report-ID 0x00 if kernel passes 65 bytes */
            if (sz == 65 && d[0] == 0x00) { d++; sz = 64; }
            if (sz < 64) continue;

            uint32_t cid = ((uint32_t)d[0] << 24) | ((uint32_t)d[1] << 16) |
                           ((uint32_t)d[2] << 8) | d[3];
            if (d[4] & CTAPHID_TYPE_INIT) {
                cur_cid = cid;
                cur_cmd = d[4] & 0x7fu;
                cur_total = (uint16_t)(((uint16_t)d[5] << 8) | d[6]);
                if (cur_total > sizeof(cur_buf)) cur_total = sizeof(cur_buf);
                size_t chunk = cur_total < 57 ? cur_total : 57;
                memcpy(cur_buf, d + 7, chunk);
                cur_got = chunk;
            } else if (cid == cur_cid && cur_got < cur_total) {
                size_t rem = cur_total - cur_got;
                size_t chunk = rem < 59 ? rem : 59;
                memcpy(cur_buf + cur_got, d + 5, chunk);
                cur_got += chunk;
            }

            if (cur_got >= cur_total && cur_cmd != 0) {
                uint8_t cmd = cur_cmd;
                cur_cmd = 0;
                dispatch_ctaphid(cur_cid, cmd, cur_buf, cur_total);
            }
        }
    }

    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_DESTROY;
    (void)write(g_uhid_fd, &ev, sizeof(ev));
    close(g_uhid_fd);
    return 0;
}
