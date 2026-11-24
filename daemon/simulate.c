#include "simulate.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Noise amplitudes (triangular noise, peaked at zero): the same shape
 * as the evaluation scenarios, which use Gaussian noise. */
#define TEMP_NOISE 0.3
#define RH_NOISE 1.0
#define RAMP_NOISE 0.1
#define HOLD_NOISE 0.2
#define STUCK_AFTER 30 /* normal readings before the sensor sticks */
#define BLIP_ONE_IN 300 /* one blip per ~300 readings (~25 min at 5 s) */

void sim_init(SensorSim *sim, const SensorConfig *cfg, unsigned int seed) {
    memset(sim, 0, sizeof(*sim));
    sim->cfg = *cfg;
    sim->rng = seed == 0 ? 1u : seed; /* rand_r() needs a non-zero seed */
    sim->step = 0;
    sim->stuck_value = 0.0;
}

/* Uniform double in [0, 1). */
static double uniform01(SensorSim *sim) {
    return (double)rand_r(&sim->rng) / ((double)RAND_MAX + 1.0);
}

/* Triangular noise in [-amp, +amp], peaked at zero: the sum of two
 * uniforms minus one, a cheap stand-in for Gaussian noise. */
static double tri_noise(SensorSim *sim, double amp) {
    return (uniform01(sim) + uniform01(sim) - 1.0) * amp;
}

static double normal_value(SensorSim *sim) {
    double amp = sim->cfg.kind == KIND_TEMP ? TEMP_NOISE : RH_NOISE;
    return sim->cfg.base + tri_noise(sim, amp);
}

/* Roll for a single-reading blip. Returns 1 and writes the blip value
 * when this reading is a blip, 0 otherwise. A blip is consumed by the
 * reading it lands on: the next reading is normal again, which is what
 * makes it a blip and not an incident. (The value goes through an
 * out-parameter, not a 0.0 sentinel, because a blip value could
 * legitimately be 0.0 for an unusual base.) */
static int maybe_blip(SensorSim *sim, double *value_out) {
    if (uniform01(sim) >= 1.0 / (double)BLIP_ONE_IN) {
        return 0;
    }
    if (sim->cfg.kind == KIND_TEMP) {
        *value_out = sim->cfg.base + 6.5; /* ~28.5 C on a 22 C base */
    } else {
        *value_out = uniform01(sim) < 0.5 ? 65.0 : 38.0; /* high or low humidity blip */
    }
    return 1;
}

Reading sim_sample(SensorSim *sim, time_t now) {
    Reading reading;
    double value;

    memset(&reading, 0, sizeof(reading));
    if (snprintf(reading.sensor_id, sizeof(reading.sensor_id), "%s", sim->cfg.sensor_id) < 0) {
        reading.sensor_id[0] = '\0';
    }
    if (format_ts_utc(now, reading.ts, sizeof(reading.ts)) != 0) {
        reading.ts[0] = '\0';
    }

    switch (sim->cfg.mode) {
        case MODE_COOLING_FAILURE:
            if (sim->cfg.kind == KIND_TEMP) {
                /* +0.5 C per reading to base+10, then hold with noise
                 * (the hold must vary, or the API's flatline rule
                 * would call a genuinely hot room a stuck sensor). */
                double target = sim->cfg.base + 0.5 * (double)(sim->step + 1);
                if (target >= sim->cfg.base + 10.0) {
                    value = sim->cfg.base + 10.0 + tri_noise(sim, HOLD_NOISE);
                } else {
                    value = target + tri_noise(sim, RAMP_NOISE);
                }
            } else {
                value = normal_value(sim); /* unreachable: config downgrades this */
            }
            break;
        case MODE_STUCK:
            if (sim->step < STUCK_AFTER) {
                value = normal_value(sim);
            } else {
                if (sim->step == STUCK_AFTER) {
                    sim->stuck_value = sim->cfg.base + 0.5; /* in-range flatline */
                }
                value = sim->stuck_value;
            }
            break;
        case MODE_NORMAL:
        default: {
            double blip_value = 0.0;
            if (maybe_blip(sim, &blip_value)) {
                value = blip_value;
            } else {
                value = normal_value(sim);
            }
            break;
        }
    }
    sim->step++;

    if (sim->cfg.kind == KIND_TEMP) {
        reading.temp_c = value;
        reading.has_temp = 1;
    } else {
        reading.rh_pct = value;
        reading.has_rh = 1;
    }
    return reading;
}
