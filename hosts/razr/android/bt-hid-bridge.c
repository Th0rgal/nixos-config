/*
 * bt-hid-bridge.c — Static ARM64 Bluetooth HID (evdev) -> local wayvnc (RFB 3.8) bridge.
 *
 * Grabs all BUS_BLUETOOTH (/dev/input/event*) keyboard & mouse devices via EVIOCGRAB
 * (so Android stops receiving their input) and injects key/pointer/scroll events
 * straight into local wayvnc at 127.0.0.1:5900. Releases grab cleanly on SIGTERM/SIGINT.
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#define MAX_DEVS 16
#define VNC_HOST "127.0.0.1"
#define VNC_PORT 5900

static volatile sig_atomic_t g_running = 1;

static void on_signal(int sig) {
    (void)sig;
    g_running = 0;
}

typedef struct {
    int fd;
    char path[64];
    char name[128];
} DevEntry;

static DevEntry g_devs[MAX_DEVS];
static int g_dev_count = 0;

static int g_vnc_fd = -1;
static int g_fb_w = 1920;
static int g_fb_h = 1080;
static double g_px = 960.0;
static double g_py = 540.0;
static uint8_t g_btn_mask = 0;
static int g_shift_down = 0;

static int read_exact(int fd, void *buf, size_t n) {
    uint8_t *p = (uint8_t *)buf;
    while (n > 0) {
        ssize_t r = read(fd, p, n);
        if (r <= 0) {
            if (r < 0 && errno == EINTR) continue;
            return -1;
        }
        p += r;
        n -= (size_t)r;
    }
    return 0;
}

static int write_exact(int fd, const void *buf, size_t n) {
    const uint8_t *p = (const uint8_t *)buf;
    while (n > 0) {
        ssize_t w = write(fd, p, n);
        if (w <= 0) {
            if (w < 0 && errno == EINTR) continue;
            return -1;
        }
        p += w;
        n -= (size_t)w;
    }
    return 0;
}

static void vnc_close(void) {
    if (g_vnc_fd >= 0) {
        close(g_vnc_fd);
        g_vnc_fd = -1;
    }
}

static int vnc_connect(int port) {
    vnc_close();
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    struct timeval tv = { .tv_sec = 3, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    inet_pton(AF_INET, VNC_HOST, &addr.sin_addr);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }

    char ver[12];
    if (read_exact(fd, ver, 12) < 0) { close(fd); return -1; }
    if (write_exact(fd, "RFB 003.008\n", 12) < 0) { close(fd); return -1; }

    uint8_t nsec = 0;
    if (read_exact(fd, &nsec, 1) < 0 || nsec == 0) { close(fd); return -1; }
    uint8_t sectypes[256];
    if (read_exact(fd, sectypes, nsec) < 0) { close(fd); return -1; }

    uint8_t chosen = 1; /* None */
    int has_none = 0;
    for (int i = 0; i < nsec; i++) {
        if (sectypes[i] == 1) { has_none = 1; break; }
    }
    if (!has_none) { close(fd); return -1; }
    if (write_exact(fd, &chosen, 1) < 0) { close(fd); return -1; }

    uint32_t sec_result = 1;
    if (read_exact(fd, &sec_result, 4) < 0 || ntohl(sec_result) != 0) {
        close(fd);
        return -1;
    }

    /* ClientInit: shared = 1 (so TigerVNC / AVNC can view simultaneously!) */
    uint8_t shared = 1;
    if (write_exact(fd, &shared, 1) < 0) { close(fd); return -1; }

    /* ServerInit: 2B width, 2B height, 16B pixelformat, 4B name_len, name */
    uint8_t sinit[24];
    if (read_exact(fd, sinit, 24) < 0) { close(fd); return -1; }
    g_fb_w = (sinit[0] << 8) | sinit[1];
    g_fb_h = (sinit[2] << 8) | sinit[3];
    if (g_fb_w <= 0) g_fb_w = 1920;
    if (g_fb_h <= 0) g_fb_h = 1080;
    uint32_t name_len = ((uint32_t)sinit[20] << 24) | ((uint32_t)sinit[21] << 16) |
                        ((uint32_t)sinit[22] << 8)  | (uint32_t)sinit[23];
    if (name_len > 0 && name_len < 4096) {
        char *tmp = malloc(name_len);
        if (tmp) {
            if (read_exact(fd, tmp, name_len) < 0) { free(tmp); close(fd); return -1; }
            free(tmp);
        }
    }

    g_px = g_fb_w / 2.0;
    g_py = g_fb_h / 2.0;
    g_vnc_fd = fd;
    fprintf(stderr, "[bt-hid-bridge] Connected to wayvnc 127.0.0.1:%d (%dx%d)\n", port, g_fb_w, g_fb_h);
    return 0;
}

static void vnc_send_pointer(uint8_t mask, int x, int y) {
    if (g_vnc_fd < 0) return;
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x >= g_fb_w) x = g_fb_w - 1;
    if (y >= g_fb_h) y = g_fb_h - 1;
    uint8_t msg[6];
    msg[0] = 5; /* PointerEvent */
    msg[1] = mask;
    msg[2] = (uint8_t)((x >> 8) & 0xFF);
    msg[3] = (uint8_t)(x & 0xFF);
    msg[4] = (uint8_t)((y >> 8) & 0xFF);
    msg[5] = (uint8_t)(y & 0xFF);
    if (write_exact(g_vnc_fd, msg, 6) < 0) vnc_close();
}

static void vnc_send_key(uint32_t keysym, int down) {
    if (g_vnc_fd < 0 || keysym == 0) return;
    uint8_t msg[8];
    msg[0] = 4; /* KeyEvent */
    msg[1] = down ? 1 : 0;
    msg[2] = 0;
    msg[3] = 0;
    msg[4] = (uint8_t)((keysym >> 24) & 0xFF);
    msg[5] = (uint8_t)((keysym >> 16) & 0xFF);
    msg[6] = (uint8_t)((keysym >> 8) & 0xFF);
    msg[7] = (uint8_t)(keysym & 0xFF);
    if (write_exact(g_vnc_fd, msg, 8) < 0) vnc_close();
}

static uint32_t evdev_to_keysym(uint16_t code, int shift) {
    switch (code) {
        case KEY_ESC:        return 0xff1b;
        case KEY_BACKSPACE:  return 0xff08;
        case KEY_TAB:        return 0xff09;
        case KEY_ENTER:      return 0xff0d;
        case KEY_KPENTER:    return 0xff8d;
        case KEY_SPACE:      return 0x0020;
        case KEY_CAPSLOCK:   return 0xffe5;
        case KEY_LEFTSHIFT:  return 0xffe1;
        case KEY_RIGHTSHIFT: return 0xffe2;
        case KEY_LEFTCTRL:   return 0xffe3;
        case KEY_RIGHTCTRL:  return 0xffe4;
        case KEY_LEFTALT:    return 0xffe9;
        case KEY_RIGHTALT:   return 0xffea;
        case KEY_LEFTMETA:   return 0xffeb;
        case KEY_RIGHTMETA:  return 0xffec;
        case KEY_HOME:       return 0xff50;
        case KEY_LEFT:       return 0xff51;
        case KEY_UP:         return 0xff52;
        case KEY_RIGHT:      return 0xff53;
        case KEY_DOWN:       return 0xff54;
        case KEY_PAGEUP:     return 0xff55;
        case KEY_PAGEDOWN:   return 0xff56;
        case KEY_END:        return 0xff57;
        case KEY_INSERT:     return 0xff63;
        case KEY_DELETE:     return 0xffff;
        case KEY_F1:  return 0xffbe; case KEY_F2:  return 0xffbf;
        case KEY_F3:  return 0xffc0; case KEY_F4:  return 0xffc1;
        case KEY_F5:  return 0xffc2; case KEY_F6:  return 0xffc3;
        case KEY_F7:  return 0xffc4; case KEY_F8:  return 0xffc5;
        case KEY_F9:  return 0xffc6; case KEY_F10: return 0xffc7;
        case KEY_F11: return 0xffc8; case KEY_F12: return 0xffc9;

        case KEY_1: return shift ? '!' : '1';
        case KEY_2: return shift ? '@' : '2';
        case KEY_3: return shift ? '#' : '3';
        case KEY_4: return shift ? '$' : '4';
        case KEY_5: return shift ? '%' : '5';
        case KEY_6: return shift ? '^' : '6';
        case KEY_7: return shift ? '&' : '7';
        case KEY_8: return shift ? '*' : '8';
        case KEY_9: return shift ? '(' : '9';
        case KEY_0: return shift ? ')' : '0';
        case KEY_MINUS:      return shift ? '_' : '-';
        case KEY_EQUAL:      return shift ? '+' : '=';
        case KEY_LEFTBRACE:  return shift ? '{' : '[';
        case KEY_RIGHTBRACE: return shift ? '}' : ']';
        case KEY_SEMICOLON:  return shift ? ':' : ';';
        case KEY_APOSTROPHE: return shift ? '"' : '\'';
        case KEY_GRAVE:      return shift ? '~' : '`';
        case KEY_BACKSLASH:  return shift ? '|' : '\\';
        case KEY_COMMA:      return shift ? '<' : ',';
        case KEY_DOT:        return shift ? '>' : '.';
        case KEY_SLASH:      return shift ? '?' : '/';

        case KEY_Q: return shift ? 'Q' : 'q'; case KEY_W: return shift ? 'W' : 'w';
        case KEY_E: return shift ? 'E' : 'e'; case KEY_R: return shift ? 'R' : 'r';
        case KEY_T: return shift ? 'T' : 't'; case KEY_Y: return shift ? 'Y' : 'y';
        case KEY_U: return shift ? 'U' : 'u'; case KEY_I: return shift ? 'I' : 'i';
        case KEY_O: return shift ? 'O' : 'o'; case KEY_P: return shift ? 'P' : 'p';
        case KEY_A: return shift ? 'A' : 'a'; case KEY_S: return shift ? 'S' : 's';
        case KEY_D: return shift ? 'D' : 'd'; case KEY_F: return shift ? 'F' : 'f';
        case KEY_G: return shift ? 'G' : 'g'; case KEY_H: return shift ? 'H' : 'h';
        case KEY_J: return shift ? 'J' : 'j'; case KEY_K: return shift ? 'K' : 'k';
        case KEY_L: return shift ? 'L' : 'l'; case KEY_Z: return shift ? 'Z' : 'z';
        case KEY_X: return shift ? 'X' : 'x'; case KEY_C: return shift ? 'C' : 'c';
        case KEY_V: return shift ? 'V' : 'v'; case KEY_B: return shift ? 'B' : 'b';
        case KEY_N: return shift ? 'N' : 'n'; case KEY_M: return shift ? 'M' : 'm';
        default: return 0;
    }
}

static int is_already_open(const char *path) {
    for (int i = 0; i < g_dev_count; i++) {
        if (strcmp(g_devs[i].path, path) == 0) return 1;
    }
    return 0;
}

static void scan_bt_devices(void) {
    DIR *d = opendir("/dev/input");
    if (!d) return;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL && g_dev_count < MAX_DEVS) {
        if (strncmp(ent->d_name, "event", 5) != 0) continue;
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/%s", ent->d_name);
        if (is_already_open(path)) continue;

        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        struct input_id id;
        memset(&id, 0, sizeof(id));
        if (ioctl(fd, EVIOCGID, &id) < 0 || id.bustype != BUS_BLUETOOTH) {
            close(fd);
            continue;
        }

        char name[128] = "Bluetooth HID";
        ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);

        if (ioctl(fd, EVIOCGRAB, (void *)1) < 0) {
            fprintf(stderr, "[bt-hid-bridge] Failed EVIOCGRAB on %s (%s): %s\n", path, name, strerror(errno));
            close(fd);
            continue;
        }

        g_devs[g_dev_count].fd = fd;
        strncpy(g_devs[g_dev_count].path, path, sizeof(g_devs[g_dev_count].path) - 1);
        strncpy(g_devs[g_dev_count].name, name, sizeof(g_devs[g_dev_count].name) - 1);
        g_dev_count++;
        fprintf(stderr, "[bt-hid-bridge] Grabbed %s (%s)\n", path, name);
    }
    closedir(d);
}

static void remove_dev(int idx) {
    if (idx < 0 || idx >= g_dev_count) return;
    fprintf(stderr, "[bt-hid-bridge] Released %s (%s)\n", g_devs[idx].path, g_devs[idx].name);
    ioctl(g_devs[idx].fd, EVIOCGRAB, (void *)0);
    close(g_devs[idx].fd);
    for (int j = idx; j < g_dev_count - 1; j++) g_devs[j] = g_devs[j + 1];
    g_dev_count--;
}

static void release_all_devs(void) {
    while (g_dev_count > 0) remove_dev(g_dev_count - 1);
}

static void handle_event(const struct input_event *ev) {
    if (ev->type == EV_REL) {
        if (ev->code == REL_X) {
            g_px += (double)ev->value * 1.15;
            if (g_px < 0) g_px = 0;
            if (g_px >= g_fb_w) g_px = g_fb_w - 1;
            vnc_send_pointer(g_btn_mask, (int)g_px, (int)g_py);
        } else if (ev->code == REL_Y) {
            g_py += (double)ev->value * 1.15;
            if (g_py < 0) g_py = 0;
            if (g_py >= g_fb_h) g_py = g_fb_h - 1;
            vnc_send_pointer(g_btn_mask, (int)g_px, (int)g_py);
        } else if (ev->code == REL_WHEEL) {
            uint8_t wbit = (ev->value > 0) ? (1 << 3) : (1 << 4);
            vnc_send_pointer(g_btn_mask | wbit, (int)g_px, (int)g_py);
            vnc_send_pointer(g_btn_mask, (int)g_px, (int)g_py);
        } else if (ev->code == REL_HWHEEL) {
            uint8_t wbit = (ev->value > 0) ? (1 << 6) : (1 << 5);
            vnc_send_pointer(g_btn_mask | wbit, (int)g_px, (int)g_py);
            vnc_send_pointer(g_btn_mask, (int)g_px, (int)g_py);
        }
    } else if (ev->type == EV_KEY) {
        if (ev->value == 2) return; /* ignore repeat, compositor handles key repeat */
        int down = (ev->value != 0);
        if (ev->code == BTN_LEFT) {
            if (down) g_btn_mask |= (1 << 0); else g_btn_mask &= ~(1 << 0);
            vnc_send_pointer(g_btn_mask, (int)g_px, (int)g_py);
        } else if (ev->code == BTN_MIDDLE) {
            if (down) g_btn_mask |= (1 << 1); else g_btn_mask &= ~(1 << 1);
            vnc_send_pointer(g_btn_mask, (int)g_px, (int)g_py);
        } else if (ev->code == BTN_RIGHT) {
            if (down) g_btn_mask |= (1 << 2); else g_btn_mask &= ~(1 << 2);
            vnc_send_pointer(g_btn_mask, (int)g_px, (int)g_py);
        } else {
            if (ev->code == KEY_LEFTSHIFT || ev->code == KEY_RIGHTSHIFT) {
                g_shift_down = down;
            }
            uint32_t ks = evdev_to_keysym(ev->code, g_shift_down);
            if (ks != 0) vnc_send_key(ks, down);
        }
    }
}

int main(int argc, char **argv) {
    int port = VNC_PORT;
    if (argc > 1) port = atoi(argv[1]);
    if (port <= 0) port = VNC_PORT;

    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGPIPE, SIG_IGN);

    time_t last_scan = 0;
    time_t last_vnc_try = 0;

    while (g_running) {
        time_t now = time(NULL);
        if (now - last_scan >= 2) {
            scan_bt_devices();
            last_scan = now;
        }
        if (g_vnc_fd < 0 && now - last_vnc_try >= 2) {
            vnc_connect(port);
            last_vnc_try = now;
        }

        struct pollfd pfds[MAX_DEVS + 1];
        int nfds = 0;
        for (int i = 0; i < g_dev_count; i++) {
            pfds[nfds].fd = g_devs[i].fd;
            pfds[nfds].events = POLLIN;
            nfds++;
        }
        int vnc_poll_idx = -1;
        if (g_vnc_fd >= 0) {
            vnc_poll_idx = nfds;
            pfds[nfds].fd = g_vnc_fd;
            pfds[nfds].events = POLLIN;
            nfds++;
        }

        int rc = poll(pfds, (nfds_t)nfds, 500);
        if (rc <= 0) continue;

        if (vnc_poll_idx >= 0 && (pfds[vnc_poll_idx].revents & (POLLIN | POLLHUP | POLLERR))) {
            uint8_t drain[512];
            ssize_t r = read(g_vnc_fd, drain, sizeof(drain));
            if (r <= 0) vnc_close();
        }

        for (int i = g_dev_count - 1; i >= 0; i--) {
            if (pfds[i].revents & (POLLHUP | POLLERR)) {
                remove_dev(i);
                continue;
            }
            if (pfds[i].revents & POLLIN) {
                struct input_event evs[32];
                ssize_t n = read(g_devs[i].fd, evs, sizeof(evs));
                if (n < 0) {
                    if (errno != EAGAIN && errno != EINTR) remove_dev(i);
                    continue;
                }
                int count = (int)(n / (ssize_t)sizeof(struct input_event));
                for (int k = 0; k < count; k++) {
                    handle_event(&evs[k]);
                }
            }
        }
    }

    release_all_devs();
    vnc_close();
    return 0;
}
