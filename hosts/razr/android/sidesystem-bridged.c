/*
 * sidesystem-bridged.c — Event-driven Android host bridge daemon for SideSystem.
 *
 * Replaces the 0.5s shell polling loop (`sidesystem-powerd`) with a zero-wakeup
 * poll()-based Unix domain socket server at:
 *   /data/local/nixos/rootfs/var/lib/sidesystem/bridge.sock
 * (visible inside the NixOS container at /var/lib/sidesystem/bridge.sock).
 *
 * Supported line-delimited commands:
 *   PING
 *   BIO_REQ <nonce>|<reason>
 *   BIO_RESP <response-line>
 *   PROFILE_SET <eco|power-saver|balanced|full|performance>
 *   CHARGE_CAP_SET <off|0|60..100>
 *   CHARGE_CAP_GET
 *   HINGE_GET
 *   BONSAI_START [npu|vulkan]
 *   BONSAI_STOP
 *   BONSAI_STATUS
 *
 * Also runs:
 *   - Closed-loop thermal governor ONLY while performance profile is active.
 *   - Battery charge-cap hysteresis enforcer ONLY while charge_cap > 0.
 *
 * Zero external library dependencies (pure POSIX/Linux C for Android & NixOS).
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define BASE_DIR          "/data/local/nixos"
#define SHARED_DIR        "/data/local/nixos/rootfs/var/lib/sidesystem"
#define SOCK_PATH         SHARED_DIR "/bridge.sock"
#define PID_PATH          BASE_DIR "/bridged.pid"
#define PROF_FILE         SHARED_DIR "/power-profile"
#define CHARGE_CAP_FILE   SHARED_DIR "/charge-cap"
#define HINGE_STATE_FILE  SHARED_DIR "/hinge-state"
#define LEGACY_BIO_RESP   SHARED_DIR "/bio-resp"
#define CTL_BIN           BASE_DIR "/bin/sidesystem-ctl"

#define MAX_CLIENTS       16
#define BIO_TIMEOUT_SEC   35

static volatile sig_atomic_t g_running = 1;

static int g_pending_bio_fd = -1;
static time_t g_pending_bio_deadline = 0;

static char g_active_profile[32] = "balanced";
static int g_thermal_stepped = 0;
static int g_charge_cap = 0; /* 0 = disabled, otherwise 60..100 */
static bool g_charge_suspended = false;
static char g_hinge_sysfs[512] = {0};

static void on_signal(int sig) {
    (void)sig;
    g_running = 0;
}

static int read_file_str(const char *path, char *buf, size_t sz) {
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    size_t n = fread(buf, 1, sz - 1, f);
    fclose(f);
    buf[n] = '\0';
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r' || buf[n - 1] == ' '))
        buf[--n] = '\0';
    return 0;
}

static int write_file_str(const char *path, const char *val) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return -1;
    size_t len = strlen(val);
    ssize_t w = write(fd, val, len);
    close(fd);
    return (w == (ssize_t)len) ? 0 : -1;
}

static int write_sysfs(const char *path, const char *val) {
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    size_t len = strlen(val);
    ssize_t w = write(fd, val, len);
    close(fd);
    return (w == (ssize_t)len) ? 0 : -1;
}

static void send_line(int fd, const char *line) {
    if (fd < 0) return;
    size_t len = strlen(line);
    (void)send(fd, line, len, MSG_NOSIGNAL);
    if (len == 0 || line[len - 1] != '\n') {
        (void)send(fd, "\n", 1, MSG_NOSIGNAL);
    }
}

static void spawn_detached(char *const argv[]) {
    pid_t pid = fork();
    if (pid == 0) {
        int devnull = open("/dev/null", O_RDWR);
        if (devnull >= 0) {
            dup2(devnull, STDIN_FILENO);
            dup2(devnull, STDOUT_FILENO);
            dup2(devnull, STDERR_FILENO);
            if (devnull > 2) close(devnull);
        }
        execv(argv[0], argv);
        _exit(127);
    }
}

static void reap_children(void) {
    while (waitpid(-1, NULL, WNOHANG) > 0) {}
}

/* ---------------- Hinge / Hall sensor detection ---------------- */
static void detect_hinge_sysfs(void) {
    /* Motorola Razr 60 Ultra native active-low Hall sensor: 0 = folded closed, 1 = open */
    if (access("/sys/class/hall/rawdata", R_OK) == 0) {
        snprintf(g_hinge_sysfs, sizeof(g_hinge_sysfs), "/sys/class/hall/rawdata");
        return;
    }
    static const char *const candidates[] = {
        "/sys/class/switch/hall/state",
        "/sys/devices/virtual/switch/hall/state",
        "/sys/class/extcon/hall/state",
        "/sys/class/hall_sensor/hall/state",
        "/sys/bus/platform/devices/soc:hall_sensor/state",
        "/sys/kernel/hall/state",
        NULL
    };
    for (int i = 0; candidates[i]; i++) {
        if (access(candidates[i], R_OK) == 0) {
            snprintf(g_hinge_sysfs, sizeof(g_hinge_sysfs), "%s", candidates[i]);
            return;
        }
    }
    /* Scan /sys/class/extcon for any hall/lid/fold device */
    DIR *d = opendir("/sys/class/extcon");
    if (d) {
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] == '.') continue;
            char name_path[512], name_val[64];
            snprintf(name_path, sizeof(name_path), "/sys/class/extcon/%.200s/name", de->d_name);
            if (read_file_str(name_path, name_val, sizeof(name_val)) == 0) {
                if (strcasestr(name_val, "hall") || strcasestr(name_val, "flip") ||
                    strcasestr(name_val, "fold") || strcasestr(name_val, "lid")) {
                    snprintf(g_hinge_sysfs, sizeof(g_hinge_sysfs),
                             "/sys/class/extcon/%.200s/state", de->d_name);
                    closedir(d);
                    return;
                }
            }
        }
        closedir(d);
    }
}

static const char *read_hinge_state(void) {
    if (g_hinge_sysfs[0] == '\0') {
        detect_hinge_sysfs();
    }
    if (g_hinge_sysfs[0] == '\0') {
        return "unknown";
    }
    char buf[128];
    if (read_file_str(g_hinge_sysfs, buf, sizeof(buf)) != 0) {
        return "unknown";
    }
    /* Razr 60 Ultra /sys/class/hall/rawdata is active-low: 0 = CLOSED_HALL, 1 = open */
    if (strcmp(g_hinge_sysfs, "/sys/class/hall/rawdata") == 0) {
        if (strcmp(buf, "0") == 0) return "closed";
        if (strcmp(buf, "1") == 0) return "open";
        return "unknown";
    }
    /* Generic switch/extcon: "1" or "HALL=1" or "closed" means folded shut */
    if (strcmp(buf, "1") == 0 || strcasestr(buf, "=1") != NULL || strcasestr(buf, "close") != NULL) {
        return "closed";
    }
    if (strcmp(buf, "0") == 0 || strcasestr(buf, "=0") != NULL || strcasestr(buf, "open") != NULL) {
        return "open";
    }
    return "unknown";
}

/* ---------------- Battery Charge Cap / Bypass ---------------- */
static void set_charging_suspended(bool suspend) {
    const char *suspend_val = suspend ? "1" : "0";
    const char *enable_val  = suspend ? "0" : "1";

    /* Try standard Qualcomm / Motorola battery sysfs knobs in order */
    if (write_sysfs("/sys/class/power_supply/battery/input_suspend", suspend_val) == 0) {
        g_charge_suspended = suspend;
        return;
    }
    if (write_sysfs("/sys/class/qcom-battery/input_suspend", suspend_val) == 0) {
        g_charge_suspended = suspend;
        return;
    }
    if (write_sysfs("/sys/class/power_supply/battery/charging_enabled", enable_val) == 0) {
        g_charge_suspended = suspend;
        return;
    }
    if (write_sysfs("/sys/class/power_supply/battery/battery_charging_enabled", enable_val) == 0) {
        g_charge_suspended = suspend;
        return;
    }
    if (write_sysfs("/sys/class/power_supply/battery/charge_disable", suspend_val) == 0) {
        g_charge_suspended = suspend;
        return;
    }
}

static void enforce_charge_cap(void) {
    if (g_charge_cap <= 0 || g_charge_cap >= 100) {
        /* Disable native Motorola charge_control thresholds (0 = unrestricted) */
        (void)write_sysfs("/sys/class/power_supply/battery/charge_control_end_threshold", "0");
        (void)write_sysfs("/sys/class/power_supply/battery/charge_control_start_threshold", "0");
        if (g_charge_suspended) {
            set_charging_suspended(false);
        }
        return;
    }

    /* Razr 60 Ultra supports native charge_control_start/end_threshold in kernel */
    char end_str[16], start_str[16];
    int start_pct = (g_charge_cap > 5) ? (g_charge_cap - 5) : g_charge_cap;
    snprintf(end_str, sizeof(end_str), "%d", g_charge_cap);
    snprintf(start_str, sizeof(start_str), "%d", start_pct);
    bool native_ok = (write_sysfs("/sys/class/power_supply/battery/charge_control_start_threshold", start_str) == 0 &&
                      write_sysfs("/sys/class/power_supply/battery/charge_control_end_threshold", end_str) == 0);
    if (native_ok) {
        return;
    }

    /* Fallback hysteresis via input_suspend / charging_enabled */
    char cap_buf[32];
    if (read_file_str("/sys/class/power_supply/battery/capacity", cap_buf, sizeof(cap_buf)) != 0) {
        return;
    }
    int pct = atoi(cap_buf);
    if (!g_charge_suspended && pct >= g_charge_cap) {
        set_charging_suspended(true);
    } else if (g_charge_suspended && pct <= start_pct) {
        set_charging_suspended(false);
    }
}

static void apply_charge_cap_setting(int cap) {
    if (cap < 0) cap = 0;
    if (cap > 0 && cap < 60) cap = 60;
    if (cap >= 100) cap = 0;
    g_charge_cap = cap;

    char buf[16];
    snprintf(buf, sizeof(buf), "%d\n", g_charge_cap);
    (void)write_file_str(CHARGE_CAP_FILE, buf);
    enforce_charge_cap();
}

/* ---------------- Closed-loop Thermal Governor ---------------- */
static void tick_thermal_governor(void) {
    if (strcmp(g_active_profile, "performance") != 0 &&
        strcmp(g_active_profile, "full") != 0) {
        return;
    }
    char soc_buf[32] = "0", batt_buf[32] = "0";
    (void)read_file_str("/sys/class/thermal/thermal_zone22/temp", soc_buf, sizeof(soc_buf));
    (void)read_file_str("/sys/class/thermal/thermal_zone67/temp", batt_buf, sizeof(batt_buf));
    int soc_mc = atoi(soc_buf);
    int batt_mc = atoi(batt_buf);

    if (g_thermal_stepped == 0 && (soc_mc >= 80000 || batt_mc >= 40000)) {
        (void)write_sysfs("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq", "3532800");
        (void)write_sysfs("/sys/class/kgsl/kgsl-3d0/min_pwrlevel", "9");
        g_thermal_stepped = 1;
    } else if (g_thermal_stepped == 1 && soc_mc < 72000 && batt_mc < 37000) {
        (void)write_sysfs("/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq", "4320000");
        (void)write_sysfs("/sys/class/kgsl/kgsl-3d0/min_pwrlevel", "4");
        g_thermal_stepped = 0;
    }
}

/* ---------------- Biometric Request Handling ---------------- */
static void complete_pending_bio(const char *resp_line) {
    if (g_pending_bio_fd >= 0) {
        send_line(g_pending_bio_fd, resp_line);
        close(g_pending_bio_fd);
        g_pending_bio_fd = -1;
        g_pending_bio_deadline = 0;
    }
}

static void check_legacy_bio_resp(void) {
    if (g_pending_bio_fd < 0) return;
    char line[1024];
    if (read_file_str(LEGACY_BIO_RESP, line, sizeof(line)) == 0 && line[0] != '\0') {
        (void)write_file_str(LEGACY_BIO_RESP, "");
        complete_pending_bio(line);
    }
}

/* ---------------- Command Dispatcher ---------------- */
static void handle_client(int fd) {
    char buf[2048];
    ssize_t n = recv(fd, buf, sizeof(buf) - 1, 0);
    if (n <= 0) {
        close(fd);
        return;
    }
    buf[n] = '\0';
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) buf[--n] = '\0';

    if (strcmp(buf, "PING") == 0) {
        send_line(fd, "PONG");
        close(fd);
        return;
    }

    if (strncmp(buf, "BIO_REQ ", 8) == 0) {
        const char *payload = buf + 8;
        const char *sep = strchr(payload, '|');
        char nonce[128] = {0};
        char reason[1024] = "NixOS authentication";
        if (sep) {
            size_t nlen = (size_t)(sep - payload);
            if (nlen >= sizeof(nonce)) nlen = sizeof(nonce) - 1;
            memcpy(nonce, payload, nlen);
            nonce[nlen] = '\0';
            if (sep[1] != '\0') {
                snprintf(reason, sizeof(reason), "%.1023s", sep + 1);
            }
        } else {
            snprintf(nonce, sizeof(nonce), "%.127s", payload);
        }

        if (nonce[0] == '\0') {
            send_line(fd, "ERR missing_nonce");
            close(fd);
            return;
        }

        /* Supersede any stale pending bio waiter */
        if (g_pending_bio_fd >= 0) {
            complete_pending_bio("CANCEL");
        }
        (void)write_file_str(LEGACY_BIO_RESP, "");

        g_pending_bio_fd = fd;
        g_pending_bio_deadline = time(NULL) + BIO_TIMEOUT_SEC;

        char *argv[] = {
            "/system/bin/am", "start", "--user", "0",
            "-n", "md.thomas.sidesystem/.BiometricAuthActivity",
            "--es", "nonce", nonce,
            "--es", "reason", reason,
            NULL
        };
        spawn_detached(argv);
        /* Keep fd open until BIO_RESP arrives or times out */
        return;
    }

    if (strncmp(buf, "BIO_RESP ", 9) == 0) {
        complete_pending_bio(buf + 9);
        send_line(fd, "OK");
        close(fd);
        return;
    }

    if (strncmp(buf, "PROFILE_SET ", 12) == 0) {
        const char *val = buf + 12;
        const char *ctl_prof = NULL;
        if (strcmp(val, "eco") == 0 || strcmp(val, "power-saver") == 0) {
            ctl_prof = "eco";
            snprintf(g_active_profile, sizeof(g_active_profile), "power-saver");
        } else if (strcmp(val, "balanced") == 0) {
            ctl_prof = "balanced";
            snprintf(g_active_profile, sizeof(g_active_profile), "balanced");
        } else if (strcmp(val, "full") == 0 || strcmp(val, "performance") == 0) {
            ctl_prof = "full";
            snprintf(g_active_profile, sizeof(g_active_profile), "performance");
        }
        if (!ctl_prof) {
            send_line(fd, "ERR invalid_profile");
            close(fd);
            return;
        }
        g_thermal_stepped = 0;
        char *argv[] = { CTL_BIN, "profile", (char *)ctl_prof, NULL };
        spawn_detached(argv);
        char reply[64];
        snprintf(reply, sizeof(reply), "OK %s", g_active_profile);
        send_line(fd, reply);
        close(fd);
        return;
    }

    if (strncmp(buf, "CHARGE_CAP_SET ", 15) == 0) {
        const char *val = buf + 15;
        int cap = 0;
        if (strcasecmp(val, "off") == 0 || strcmp(val, "0") == 0 || strcmp(val, "100") == 0) {
            cap = 0;
        } else {
            cap = atoi(val);
        }
        apply_charge_cap_setting(cap);
        char reply[64];
        snprintf(reply, sizeof(reply), "OK %d", g_charge_cap);
        send_line(fd, reply);
        close(fd);
        return;
    }

    if (strcmp(buf, "CHARGE_CAP_GET") == 0) {
        char reply[64];
        snprintf(reply, sizeof(reply), "OK %d", g_charge_cap);
        send_line(fd, reply);
        close(fd);
        return;
    }

    if (strcmp(buf, "HINGE_GET") == 0) {
        const char *st = read_hinge_state();
        (void)write_file_str(HINGE_STATE_FILE, st);
        char reply[64];
        snprintf(reply, sizeof(reply), "OK %s", st);
        send_line(fd, reply);
        close(fd);
        return;
    }

    if (strncmp(buf, "BONSAI_START", 12) == 0 && (buf[12] == '\0' || buf[12] == ' ')) {
        const char *mode = (buf[12] == ' ' && buf[13] != '\0') ? (buf + 13) : "npu";
        if (strcmp(mode, "npu") != 0 && strcmp(mode, "vulkan") != 0) {
            send_line(fd, "ERR invalid_bonsai_mode");
            close(fd);
            return;
        }
        pid_t pid = fork();
        if (pid == 0) {
            execl("/system/bin/sh", "sh", "/data/local/tmp/bonsai-shootout/bonsai-backend-ctl.sh", "start", mode, (char *)NULL);
            _exit(127);
        }
        if (pid > 0) {
            int st = 0;
            (void)waitpid(pid, &st, 0);
        }
        send_line(fd, "OK");
        close(fd);
        return;
    }

    if (strcmp(buf, "BONSAI_STOP") == 0) {
        pid_t pid = fork();
        if (pid == 0) {
            execl("/system/bin/sh", "sh", "/data/local/tmp/bonsai-shootout/bonsai-backend-ctl.sh", "stop", (char *)NULL);
            _exit(127);
        }
        if (pid > 0) {
            int st = 0;
            (void)waitpid(pid, &st, 0);
        }
        send_line(fd, "OK");
        close(fd);
        return;
    }

    if (strcmp(buf, "BONSAI_STATUS") == 0) {
        int rc = system("/system/bin/sh /data/local/tmp/bonsai-shootout/bonsai-backend-ctl.sh status >/dev/null 2>&1");
        send_line(fd, (rc == 0) ? "OK running" : "OK stopped");
        close(fd);
        return;
    }

    send_line(fd, "ERR unknown_command");
    close(fd);
}

static void load_initial_state(void) {
    char buf[64];
    if (read_file_str(PROF_FILE, buf, sizeof(buf)) == 0 && buf[0] != '\0') {
        snprintf(g_active_profile, sizeof(g_active_profile), "%.31s", buf);
    }
    if (read_file_str(CHARGE_CAP_FILE, buf, sizeof(buf)) == 0 && buf[0] != '\0') {
        g_charge_cap = atoi(buf);
        if (g_charge_cap > 0) enforce_charge_cap();
    }
    detect_hinge_sysfs();
    (void)write_file_str(HINGE_STATE_FILE, read_hinge_state());
}

int main(void) {
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGPIPE, SIG_IGN);

    mkdir(SHARED_DIR, 0755);
    char pid_str[32];
    snprintf(pid_str, sizeof(pid_str), "%d\n", (int)getpid());
    (void)write_file_str(PID_PATH, pid_str);

    load_initial_state();

    int srv = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (srv < 0) {
        perror("socket");
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", SOCK_PATH);

    unlink(SOCK_PATH);
    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind");
        close(srv);
        return 1;
    }
    chmod(SOCK_PATH, 0666);

    if (listen(srv, MAX_CLIENTS) < 0) {
        perror("listen");
        close(srv);
        unlink(SOCK_PATH);
        return 1;
    }

    time_t last_thermal = 0;
    time_t last_charge = 0;

    while (g_running) {
        reap_children();

        int timeout_ms = -1; /* 0 idle wakeups when nothing periodic is armed */
        bool need_thermal = (strcmp(g_active_profile, "performance") == 0 ||
                             strcmp(g_active_profile, "full") == 0);
        if (g_pending_bio_fd >= 0) {
            timeout_ms = 250;
        } else if (need_thermal) {
            timeout_ms = 2000;
        } else if (g_charge_cap > 0) {
            timeout_ms = 30000;
        }

        struct pollfd pfd = { .fd = srv, .events = POLLIN, .revents = 0 };
        int rc = poll(&pfd, 1, timeout_ms);
        if (rc > 0 && (pfd.revents & POLLIN)) {
            int cfd = accept4(srv, NULL, NULL, SOCK_CLOEXEC);
            if (cfd >= 0) {
                handle_client(cfd);
            }
        }

        time_t now = time(NULL);
        if (g_pending_bio_fd >= 0) {
            check_legacy_bio_resp();
            if (g_pending_bio_fd >= 0 && now >= g_pending_bio_deadline) {
                complete_pending_bio("ERR timeout");
            }
        }
        if (need_thermal && (now - last_thermal) >= 2) {
            last_thermal = now;
            tick_thermal_governor();
        }
        if (g_charge_cap > 0 && (now - last_charge) >= 30) {
            last_charge = now;
            enforce_charge_cap();
        }
    }

    complete_pending_bio("CANCEL");
    close(srv);
    unlink(SOCK_PATH);
    unlink(PID_PATH);
    return 0;
}
