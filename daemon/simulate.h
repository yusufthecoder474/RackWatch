#ifndef RACKWATCH_SIMULATE_H
#define RACKWATCH_SIMULATE_H

#include <time.h>

#include "config.h"
#include "reading.h"

/* Per-sensor simulation state. All state is in memory: a restart
 * restarts the ramp and the stuck counter, which is fine for a
 * simulator — the API, not the daemon, owns alert state. */
typedef struct {
    SensorConfig cfg; /* copy of this sensor's config line */
    unsigned int rng; /* rand_r() state, seeded per sensor */
    unsigned long step; /* readings generated so far */
    double stuck_value; /* captured when the sensor sticks */
} SensorSim;

/* Initialise a simulator from one config line. seed is the daemon's
 * --seed value plus the sensor index, so --seed reproduces the same
 * stream of readings on every run. */
void sim_init(SensorSim *sim, const SensorConfig *cfg, unsigned int seed);

/* Sample one reading. The timestamp is `now` — the sampling time —
 * formatted here and never changed afterwards: retries and buffer
 * flushes send the stored stamp, never a fresh one. */
Reading sim_sample(SensorSim *sim, time_t now);

#endif /* RACKWATCH_SIMULATE_H */
