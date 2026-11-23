/*
 * sidesystem-upowerd.c — Lightweight sysfs-backed org.freedesktop.UPower +
 * org.freedesktop.UPower.PowerProfiles D-Bus daemon for Android/droidspaces NixOS
 * containers where udev power_supply uevents & ACPI platform_profile are absent.
 *
 * Reads /sys/class/power_supply/battery/* and exposes:
 *   - org.freedesktop.UPower (/org/freedesktop/UPower,
 *     /org/freedesktop/UPower/devices/DisplayDevice,
 *     /org/freedesktop/UPower/devices/battery_BAT0)
 *   - org.freedesktop.UPower.PowerProfiles (/org/freedesktop/UPower/PowerProfiles)
 *   - net.hadess.PowerProfiles (/net/hadess/PowerProfiles)
 *
 * Persists the selected Power Efficiency Mode (power-saver | balanced | performance)
 * to /var/lib/sidesystem/power-profile so sidesystem-powerd on Android can tune
 * Snapdragon CPU/GPU governors & power caps accordingly.
 */
#include <gio/gio.h>
#include <glib-unix.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define PROFILE_STATE_PATH "/var/lib/sidesystem/power-profile"
#define PROFILE_REQ_PATH   "/var/lib/sidesystem/power-profile-req"

static const char *UPOWER_XML =
    "<node>"
    "  <interface name='org.freedesktop.UPower'>"
    "    <method name='EnumerateDevices'>"
    "      <arg type='ao' name='devices' direction='out'/>"
    "    </method>"
    "    <method name='GetDisplayDevice'>"
    "      <arg type='o' name='device' direction='out'/>"
    "    </method>"
    "    <method name='GetCriticalAction'>"
    "      <arg type='s' name='action' direction='out'/>"
    "    </method>"
    "    <signal name='DeviceAdded'><arg type='o' name='device'/></signal>"
    "    <signal name='DeviceRemoved'><arg type='o' name='device'/></signal>"
    "    <property name='DaemonVersion' type='s' access='read'/>"
    "    <property name='OnBattery' type='b' access='read'/>"
    "    <property name='LidIsClosed' type='b' access='read'/>"
    "    <property name='LidIsPresent' type='b' access='read'/>"
    "  </interface>"
    "</node>";

static const char *DEVICE_XML =
    "<node>"
    "  <interface name='org.freedesktop.UPower.Device'>"
    "    <method name='Refresh'/>"
    "    <property name='NativePath' type='s' access='read'/>"
    "    <property name='Vendor' type='s' access='read'/>"
    "    <property name='Model' type='s' access='read'/>"
    "    <property name='Serial' type='s' access='read'/>"
    "    <property name='UpdateTime' type='t' access='read'/>"
    "    <property name='Type' type='u' access='read'/>"
    "    <property name='PowerSupply' type='b' access='read'/>"
    "    <property name='HasHistory' type='b' access='read'/>"
    "    <property name='HasStatistics' type='b' access='read'/>"
    "    <property name='Online' type='b' access='read'/>"
    "    <property name='Energy' type='d' access='read'/>"
    "    <property name='EnergyEmpty' type='d' access='read'/>"
    "    <property name='EnergyFull' type='d' access='read'/>"
    "    <property name='EnergyFullDesign' type='d' access='read'/>"
    "    <property name='EnergyRate' type='d' access='read'/>"
    "    <property name='Voltage' type='d' access='read'/>"
    "    <property name='ChargeCycles' type='i' access='read'/>"
    "    <property name='Luminosity' type='d' access='read'/>"
    "    <property name='TimeToEmpty' type='x' access='read'/>"
    "    <property name='TimeToFull' type='x' access='read'/>"
    "    <property name='Percentage' type='d' access='read'/>"
    "    <property name='Temperature' type='d' access='read'/>"
    "    <property name='IsPresent' type='b' access='read'/>"
    "    <property name='State' type='u' access='read'/>"
    "    <property name='IsRechargeable' type='b' access='read'/>"
    "    <property name='Capacity' type='d' access='read'/>"
    "    <property name='Technology' type='u' access='read'/>"
    "    <property name='WarningLevel' type='u' access='read'/>"
    "    <property name='BatteryLevel' type='u' access='read'/>"
    "    <property name='IconName' type='s' access='read'/>"
    "    <property name='ChargeStartThreshold' type='u' access='read'/>"
    "    <property name='ChargeEndThreshold' type='u' access='read'/>"
    "    <property name='ChargeThresholdEnabled' type='b' access='read'/>"
    "    <property name='ChargeThresholdSupported' type='b' access='read'/>"
    "    <property name='ChargeThresholdSettingsSupported' type='u' access='read'/>"
    "    <property name='VoltageMinDesign' type='d' access='read'/>"
    "    <property name='VoltageMaxDesign' type='d' access='read'/>"
    "    <property name='CapacityLevel' type='s' access='read'/>"
    "  </interface>"
    "</node>";

static const char *PPD_XML =
    "<node>"
    "  <interface name='org.freedesktop.UPower.PowerProfiles'>"
    "    <method name='HoldProfile'>"
    "      <arg type='s' name='profile' direction='in'/>"
    "      <arg type='s' name='reason' direction='in'/>"
    "      <arg type='s' name='application_id' direction='in'/>"
    "      <arg type='u' name='cookie' direction='out'/>"
    "    </method>"
    "    <method name='ReleaseProfile'>"
    "      <arg type='u' name='cookie' direction='in'/>"
    "    </method>"
    "    <signal name='ProfileReleased'><arg type='u' name='cookie'/></signal>"
    "    <property name='ActiveProfile' type='s' access='readwrite'/>"
    "    <property name='PerformanceInhibited' type='s' access='read'/>"
    "    <property name='PerformanceDegraded' type='s' access='read'/>"
    "    <property name='Profiles' type='aa{sv}' access='read'/>"
    "    <property name='Actions' type='as' access='read'/>"
    "    <property name='ActionsInfo' type='aa{sv}' access='read'/>"
    "    <property name='ActiveProfileHolds' type='aa{sv}' access='read'/>"
    "    <property name='Version' type='s' access='read'/>"
    "    <property name='BatteryAware' type='b' access='readwrite'/>"
    "  </interface>"
    "</node>";

static const char *PPD_LEGACY_XML =
    "<node>"
    "  <interface name='net.hadess.PowerProfiles'>"
    "    <method name='HoldProfile'>"
    "      <arg type='s' name='profile' direction='in'/>"
    "      <arg type='s' name='reason' direction='in'/>"
    "      <arg type='s' name='application_id' direction='in'/>"
    "      <arg type='u' name='cookie' direction='out'/>"
    "    </method>"
    "    <method name='ReleaseProfile'>"
    "      <arg type='u' name='cookie' direction='in'/>"
    "    </method>"
    "    <signal name='ProfileReleased'><arg type='u' name='cookie'/></signal>"
    "    <property name='ActiveProfile' type='s' access='readwrite'/>"
    "    <property name='PerformanceInhibited' type='s' access='read'/>"
    "    <property name='PerformanceDegraded' type='s' access='read'/>"
    "    <property name='Profiles' type='aa{sv}' access='read'/>"
    "    <property name='Actions' type='as' access='read'/>"
    "    <property name='ActiveProfileHolds' type='aa{sv}' access='read'/>"
    "    <property name='Version' type='s' access='read'/>"
    "    <property name='BatteryAware' type='b' access='readwrite'/>"
    "  </interface>"
    "</node>";

typedef struct {
    GDBusConnection *conn;
    double percentage;
    guint32 state; /* 1=charging, 2=discharging, 4=fully-charged */
    gboolean on_battery;
    double energy;
    double energy_full;
    double energy_full_design;
    double energy_rate;
    double voltage;
    double temperature;
    gint32 charge_cycles;
    guint64 update_time;
    char icon_name[64];
    char active_profile[32];
    gboolean battery_aware;
    guint32 next_cookie;
} BattState;

static BattState g_st = {
    .percentage = 100.0,
    .state = 1,
    .on_battery = FALSE,
    .energy = 18.0,
    .energy_full = 18.0,
    .energy_full_design = 18.0,
    .energy_rate = 0.0,
    .voltage = 4.2,
    .temperature = 30.0,
    .charge_cycles = 0,
    .update_time = 0,
    .icon_name = "battery-full-charging-symbolic",
    .active_profile = "balanced",
    .battery_aware = TRUE,
    .next_cookie = 1,
};

static int read_str(const char *path, char *buf, size_t sz) {
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    size_t n = fread(buf, 1, sz - 1, f);
    fclose(f);
    buf[n] = '\0';
    while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r' || buf[n - 1] == ' '))
        buf[--n] = '\0';
    return 0;
}

static void write_profile_state(const char *prof) {
    FILE *f = fopen(PROFILE_STATE_PATH, "w");
    if (f) { fprintf(f, "%s\n", prof); fclose(f); }
    FILE *r = fopen(PROFILE_REQ_PATH, "w");
    if (r) { fprintf(r, "%s\n", prof); fclose(r); }
}

static void load_profile_state(void) {
    char buf[64];
    if (read_str(PROFILE_STATE_PATH, buf, sizeof(buf)) == 0) {
        if (strcmp(buf, "power-saver") == 0 ||
            strcmp(buf, "balanced") == 0 ||
            strcmp(buf, "performance") == 0) {
            snprintf(g_st.active_profile, sizeof(g_st.active_profile), "%s", buf);
        }
    }
}

static void compute_icon(double pct, guint32 st, char *out, size_t sz) {
    const char *lvl = "empty";
    if (pct >= 90) lvl = "full";
    else if (pct >= 60) lvl = "good";
    else if (pct >= 25) lvl = "low";
    else if (pct >= 10) lvl = "caution";
    if (st == 4) snprintf(out, sz, "battery-full-charged-symbolic");
    else if (st == 1) snprintf(out, sz, "battery-%s-charging-symbolic", lvl);
    else snprintf(out, sz, "battery-%s-symbolic", lvl);
}

static gboolean poll_sysfs(gpointer user_data) {
    (void)user_data;
    char buf[64];
    double old_pct = g_st.percentage;
    guint32 old_state = g_st.state;
    gboolean old_on_batt = g_st.on_battery;

    g_st.update_time = (guint64)time(NULL);

    if (read_str("/sys/class/power_supply/battery/capacity", buf, sizeof(buf)) == 0) {
        double p = atof(buf);
        if (p < 0) p = 0;
        if (p > 100) p = 100;
        g_st.percentage = p;
    }

    if (read_str("/sys/class/power_supply/battery/status", buf, sizeof(buf)) == 0) {
        if (g_ascii_strcasecmp(buf, "Charging") == 0) {
            g_st.state = 1;
            g_st.on_battery = FALSE;
        } else if (g_ascii_strcasecmp(buf, "Full") == 0) {
            g_st.state = 4;
            g_st.on_battery = FALSE;
        } else {
            g_st.state = 2;
            g_st.on_battery = TRUE;
        }
    }

    double uv = 0, ua = 0;
    if (read_str("/sys/class/power_supply/battery/voltage_now", buf, sizeof(buf)) == 0)
        uv = fabs(atof(buf));
    if (read_str("/sys/class/power_supply/battery/current_now", buf, sizeof(buf)) == 0)
        ua = fabs(atof(buf));
    if (uv > 0) g_st.voltage = uv / 1e6;
    if (uv > 0 && ua > 0) g_st.energy_rate = (uv * ua) / 1e12;

    if (read_str("/sys/class/power_supply/battery/charge_full_design", buf, sizeof(buf)) == 0) {
        double uah = fabs(atof(buf));
        if (uah > 0) g_st.energy_full_design = (uah / 1e6) * 3.88;
    }
    if (read_str("/sys/class/power_supply/battery/charge_full", buf, sizeof(buf)) == 0) {
        double uah = fabs(atof(buf));
        if (uah > 0) g_st.energy_full = (uah / 1e6) * 3.88;
    }
    if (g_st.energy_full <= 0) g_st.energy_full = 18.2;
    if (g_st.energy_full_design <= 0) g_st.energy_full_design = g_st.energy_full;
    g_st.energy = g_st.energy_full * (g_st.percentage / 100.0);

    if (read_str("/sys/class/power_supply/battery/temp", buf, sizeof(buf)) == 0)
        g_st.temperature = atof(buf) / 10.0;
    if (read_str("/sys/class/power_supply/battery/cycle_count", buf, sizeof(buf)) == 0)
        g_st.charge_cycles = atoi(buf);

    compute_icon(g_st.percentage, g_st.state, g_st.icon_name, sizeof(g_st.icon_name));

    if (g_st.conn && (old_pct != g_st.percentage || old_state != g_st.state)) {
        const char *paths[] = {
            "/org/freedesktop/UPower/devices/DisplayDevice",
            "/org/freedesktop/UPower/devices/battery_BAT0",
        };
        for (size_t k = 0; k < 2; k++) {
            GVariantBuilder b;
            g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
            g_variant_builder_add(&b, "{sv}", "Percentage", g_variant_new_double(g_st.percentage));
            g_variant_builder_add(&b, "{sv}", "State", g_variant_new_uint32(g_st.state));
            g_variant_builder_add(&b, "{sv}", "Energy", g_variant_new_double(g_st.energy));
            g_variant_builder_add(&b, "{sv}", "EnergyRate", g_variant_new_double(g_st.energy_rate));
            g_variant_builder_add(&b, "{sv}", "UpdateTime", g_variant_new_uint64(g_st.update_time));
            g_variant_builder_add(&b, "{sv}", "IconName", g_variant_new_string(g_st.icon_name));
            g_dbus_connection_emit_signal(
                g_st.conn, NULL, paths[k],
                "org.freedesktop.DBus.Properties", "PropertiesChanged",
                g_variant_new("(sa{sv}as)", "org.freedesktop.UPower.Device", &b, NULL),
                NULL);
        }
    }
    if (g_st.conn && old_on_batt != g_st.on_battery) {
        GVariantBuilder b;
        g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
        g_variant_builder_add(&b, "{sv}", "OnBattery", g_variant_new_boolean(g_st.on_battery));
        g_dbus_connection_emit_signal(
            g_st.conn, NULL,
            "/org/freedesktop/UPower",
            "org.freedesktop.DBus.Properties", "PropertiesChanged",
            g_variant_new("(sa{sv}as)", "org.freedesktop.UPower", &b, NULL),
            NULL);
    }
    return G_SOURCE_CONTINUE;
}

static void upower_method(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                          const gchar *m, GVariant *p, GDBusMethodInvocation *inv, gpointer u) {
    (void)c; (void)s; (void)o; (void)i; (void)p; (void)u;
    if (g_strcmp0(m, "EnumerateDevices") == 0) {
        const gchar *devs[] = { "/org/freedesktop/UPower/devices/battery_BAT0", NULL };
        g_dbus_method_invocation_return_value(inv, g_variant_new("(^ao)", devs));
    } else if (g_strcmp0(m, "GetDisplayDevice") == 0) {
        g_dbus_method_invocation_return_value(
            inv, g_variant_new("(o)", "/org/freedesktop/UPower/devices/DisplayDevice"));
    } else if (g_strcmp0(m, "GetCriticalAction") == 0) {
        g_dbus_method_invocation_return_value(inv, g_variant_new("(s)", "PowerOff"));
    } else {
        g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", m);
    }
}

static GVariant *upower_get_prop(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                                 const gchar *prop, GError **err, gpointer u) {
    (void)c; (void)s; (void)o; (void)i; (void)err; (void)u;
    if (g_strcmp0(prop, "DaemonVersion") == 0) return g_variant_new_string("1.91.5");
    if (g_strcmp0(prop, "OnBattery") == 0)     return g_variant_new_boolean(g_st.on_battery);
    if (g_strcmp0(prop, "LidIsClosed") == 0)   return g_variant_new_boolean(FALSE);
    if (g_strcmp0(prop, "LidIsPresent") == 0)  return g_variant_new_boolean(FALSE);
    return NULL;
}

static void dev_method(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                       const gchar *m, GVariant *p, GDBusMethodInvocation *inv, gpointer u) {
    (void)c; (void)s; (void)o; (void)i; (void)p; (void)u;
    if (g_strcmp0(m, "Refresh") == 0) {
        poll_sysfs(NULL);
        g_dbus_method_invocation_return_value(inv, NULL);
    }
}

static GVariant *dev_get_prop(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                              const gchar *prop, GError **err, gpointer u) {
    (void)c; (void)s; (void)o; (void)i; (void)err; (void)u;
    if (g_strcmp0(prop, "NativePath") == 0)        return g_variant_new_string("battery");
    if (g_strcmp0(prop, "Vendor") == 0)            return g_variant_new_string("Motorola");
    if (g_strcmp0(prop, "Model") == 0)             return g_variant_new_string("Razr 60 Ultra Battery");
    if (g_strcmp0(prop, "Serial") == 0)            return g_variant_new_string("");
    if (g_strcmp0(prop, "UpdateTime") == 0)        return g_variant_new_uint64(g_st.update_time);
    if (g_strcmp0(prop, "Type") == 0)              return g_variant_new_uint32(2); /* Battery */
    if (g_strcmp0(prop, "PowerSupply") == 0)       return g_variant_new_boolean(TRUE);
    if (g_strcmp0(prop, "HasHistory") == 0)        return g_variant_new_boolean(FALSE);
    if (g_strcmp0(prop, "HasStatistics") == 0)     return g_variant_new_boolean(FALSE);
    if (g_strcmp0(prop, "Online") == 0)            return g_variant_new_boolean(!g_st.on_battery);
    if (g_strcmp0(prop, "Energy") == 0)            return g_variant_new_double(g_st.energy);
    if (g_strcmp0(prop, "EnergyEmpty") == 0)       return g_variant_new_double(0.0);
    if (g_strcmp0(prop, "EnergyFull") == 0)        return g_variant_new_double(g_st.energy_full);
    if (g_strcmp0(prop, "EnergyFullDesign") == 0)  return g_variant_new_double(g_st.energy_full_design);
    if (g_strcmp0(prop, "EnergyRate") == 0)        return g_variant_new_double(g_st.energy_rate);
    if (g_strcmp0(prop, "Voltage") == 0)           return g_variant_new_double(g_st.voltage);
    if (g_strcmp0(prop, "ChargeCycles") == 0)      return g_variant_new_int32(g_st.charge_cycles);
    if (g_strcmp0(prop, "Luminosity") == 0)        return g_variant_new_double(0.0);
    if (g_strcmp0(prop, "TimeToEmpty") == 0)       return g_variant_new_int64(0);
    if (g_strcmp0(prop, "TimeToFull") == 0)        return g_variant_new_int64(0);
    if (g_strcmp0(prop, "Percentage") == 0)        return g_variant_new_double(g_st.percentage);
    if (g_strcmp0(prop, "Temperature") == 0)       return g_variant_new_double(g_st.temperature);
    if (g_strcmp0(prop, "IsPresent") == 0)         return g_variant_new_boolean(TRUE);
    if (g_strcmp0(prop, "State") == 0)             return g_variant_new_uint32(g_st.state);
    if (g_strcmp0(prop, "IsRechargeable") == 0)    return g_variant_new_boolean(TRUE);
    if (g_strcmp0(prop, "Capacity") == 0)          return g_variant_new_double(
        g_st.energy_full_design > 0 ? (g_st.energy_full / g_st.energy_full_design) * 100.0 : 100.0);
    if (g_strcmp0(prop, "Technology") == 0)        return g_variant_new_uint32(1); /* Lithium ion */
    if (g_strcmp0(prop, "WarningLevel") == 0)      return g_variant_new_uint32(1); /* None */
    if (g_strcmp0(prop, "BatteryLevel") == 0)      return g_variant_new_uint32(1); /* None */
    if (g_strcmp0(prop, "IconName") == 0)          return g_variant_new_string(g_st.icon_name);
    if (g_strcmp0(prop, "ChargeStartThreshold") == 0)             return g_variant_new_uint32(0);
    if (g_strcmp0(prop, "ChargeEndThreshold") == 0)               return g_variant_new_uint32(0);
    if (g_strcmp0(prop, "ChargeThresholdEnabled") == 0)           return g_variant_new_boolean(FALSE);
    if (g_strcmp0(prop, "ChargeThresholdSupported") == 0)         return g_variant_new_boolean(FALSE);
    if (g_strcmp0(prop, "ChargeThresholdSettingsSupported") == 0) return g_variant_new_uint32(0);
    if (g_strcmp0(prop, "VoltageMinDesign") == 0)  return g_variant_new_double(3.4);
    if (g_strcmp0(prop, "VoltageMaxDesign") == 0)  return g_variant_new_double(4.5);
    if (g_strcmp0(prop, "CapacityLevel") == 0)     return g_variant_new_string("Normal");
    return NULL;
}

/* --- PowerProfiles interface --- */
static void ppd_method(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                       const gchar *m, GVariant *p, GDBusMethodInvocation *inv, gpointer u) {
    (void)c; (void)s; (void)o; (void)i; (void)p; (void)u;
    if (g_strcmp0(m, "HoldProfile") == 0) {
        g_dbus_method_invocation_return_value(inv, g_variant_new("(u)", g_st.next_cookie++));
    } else if (g_strcmp0(m, "ReleaseProfile") == 0) {
        g_dbus_method_invocation_return_value(inv, NULL);
    } else {
        g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", m);
    }
}

static GVariant *build_profiles_variant(void) {
    const char *names[] = { "power-saver", "balanced", "performance" };
    GVariantBuilder arr;
    g_variant_builder_init(&arr, G_VARIANT_TYPE("aa{sv}"));
    for (size_t k = 0; k < 3; k++) {
        GVariantBuilder d;
        g_variant_builder_init(&d, G_VARIANT_TYPE_VARDICT);
        g_variant_builder_add(&d, "{sv}", "Profile", g_variant_new_string(names[k]));
        g_variant_builder_add(&d, "{sv}", "PlatformDriver", g_variant_new_string("sidesystem-qcom"));
        g_variant_builder_add(&d, "{sv}", "Driver", g_variant_new_string("sidesystem-qcom"));
        g_variant_builder_add_value(&arr, g_variant_builder_end(&d));
    }
    return g_variant_builder_end(&arr);
}

static GVariant *ppd_get_prop(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                              const gchar *prop, GError **err, gpointer u) {
    (void)c; (void)s; (void)o; (void)i; (void)err; (void)u;
    if (g_strcmp0(prop, "ActiveProfile") == 0)        return g_variant_new_string(g_st.active_profile);
    if (g_strcmp0(prop, "PerformanceInhibited") == 0) return g_variant_new_string("");
    if (g_strcmp0(prop, "PerformanceDegraded") == 0)  return g_variant_new_string("");
    if (g_strcmp0(prop, "Profiles") == 0)             return build_profiles_variant();
    if (g_strcmp0(prop, "Actions") == 0) {
        const gchar *empty[] = { NULL };
        return g_variant_new_strv(empty, 0);
    }
    if (g_strcmp0(prop, "ActionsInfo") == 0 || g_strcmp0(prop, "ActiveProfileHolds") == 0) {
        GVariantBuilder arr;
        g_variant_builder_init(&arr, G_VARIANT_TYPE("aa{sv}"));
        return g_variant_builder_end(&arr);
    }
    if (g_strcmp0(prop, "Version") == 0)      return g_variant_new_string("0.30-sidesystem");
    if (g_strcmp0(prop, "BatteryAware") == 0) return g_variant_new_boolean(g_st.battery_aware);
    return NULL;
}

static gboolean ppd_set_prop(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                             const gchar *prop, GVariant *val, GError **err, gpointer u) {
    (void)s; (void)o; (void)i; (void)u;
    if (g_strcmp0(prop, "ActiveProfile") == 0) {
        const char *p = g_variant_get_string(val, NULL);
        if (strcmp(p, "power-saver") != 0 &&
            strcmp(p, "balanced") != 0 &&
            strcmp(p, "performance") != 0) {
            g_set_error(err, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "Invalid profile: %s", p);
            return FALSE;
        }
        snprintf(g_st.active_profile, sizeof(g_st.active_profile), "%s", p);
        write_profile_state(p);

        const char *paths[] = { "/org/freedesktop/UPower/PowerProfiles", "/net/hadess/PowerProfiles" };
        const char *ifaces[] = { "org.freedesktop.UPower.PowerProfiles", "net.hadess.PowerProfiles" };
        for (size_t k = 0; k < 2; k++) {
            GVariantBuilder b;
            g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
            g_variant_builder_add(&b, "{sv}", "ActiveProfile", g_variant_new_string(g_st.active_profile));
            g_dbus_connection_emit_signal(
                c, NULL, paths[k],
                "org.freedesktop.DBus.Properties", "PropertiesChanged",
                g_variant_new("(sa{sv}as)", ifaces[k], &b, NULL),
                NULL);
        }
        return TRUE;
    }
    if (g_strcmp0(prop, "BatteryAware") == 0) {
        g_st.battery_aware = g_variant_get_boolean(val);
        return TRUE;
    }
    return FALSE;
}

static void on_bus_acquired(GDBusConnection *conn, const gchar *name, gpointer u) {
    (void)name; (void)u;
    g_st.conn = conn;
    static const GDBusInterfaceVTable up_vt  = { upower_method, upower_get_prop, NULL, {0} };
    static const GDBusInterfaceVTable dev_vt = { dev_method,    dev_get_prop,    NULL, {0} };
    static const GDBusInterfaceVTable ppd_vt = { ppd_method,    ppd_get_prop,    ppd_set_prop, {0} };

    GDBusNodeInfo *up_info   = g_dbus_node_info_new_for_xml(UPOWER_XML, NULL);
    GDBusNodeInfo *dev_info  = g_dbus_node_info_new_for_xml(DEVICE_XML, NULL);
    GDBusNodeInfo *ppd_info  = g_dbus_node_info_new_for_xml(PPD_XML, NULL);
    GDBusNodeInfo *ppdl_info = g_dbus_node_info_new_for_xml(PPD_LEGACY_XML, NULL);

    g_dbus_connection_register_object(conn, "/org/freedesktop/UPower",
                                      up_info->interfaces[0], &up_vt, NULL, NULL, NULL);
    g_dbus_connection_register_object(conn, "/org/freedesktop/UPower/devices/DisplayDevice",
                                      dev_info->interfaces[0], &dev_vt, NULL, NULL, NULL);
    g_dbus_connection_register_object(conn, "/org/freedesktop/UPower/devices/battery_BAT0",
                                      dev_info->interfaces[0], &dev_vt, NULL, NULL, NULL);
    g_dbus_connection_register_object(conn, "/org/freedesktop/UPower/PowerProfiles",
                                      ppd_info->interfaces[0], &ppd_vt, NULL, NULL, NULL);
    g_dbus_connection_register_object(conn, "/net/hadess/PowerProfiles",
                                      ppdl_info->interfaces[0], &ppd_vt, NULL, NULL, NULL);

    g_dbus_node_info_unref(up_info);
    g_dbus_node_info_unref(dev_info);
    g_dbus_node_info_unref(ppd_info);
    g_dbus_node_info_unref(ppdl_info);

    g_bus_own_name_on_connection(conn, "org.freedesktop.UPower.PowerProfiles",
                                 G_BUS_NAME_OWNER_FLAGS_ALLOW_REPLACEMENT | G_BUS_NAME_OWNER_FLAGS_REPLACE,
                                 NULL, NULL, NULL, NULL);
    g_bus_own_name_on_connection(conn, "net.hadess.PowerProfiles",
                                 G_BUS_NAME_OWNER_FLAGS_ALLOW_REPLACEMENT | G_BUS_NAME_OWNER_FLAGS_REPLACE,
                                 NULL, NULL, NULL, NULL);
}

int main(void) {
    load_profile_state();
    poll_sysfs(NULL);
    GMainLoop *loop = g_main_loop_new(NULL, FALSE);
    g_bus_own_name(G_BUS_TYPE_SYSTEM, "org.freedesktop.UPower",
                   G_BUS_NAME_OWNER_FLAGS_ALLOW_REPLACEMENT | G_BUS_NAME_OWNER_FLAGS_REPLACE,
                   on_bus_acquired, NULL, NULL, NULL, NULL);
    g_timeout_add_seconds(5, poll_sysfs, NULL);
    g_main_loop_run(loop);
    return 0;
}
