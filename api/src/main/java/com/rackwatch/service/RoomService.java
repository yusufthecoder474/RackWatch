package com.rackwatch.service;

import com.rackwatch.config.RackwatchProperties;
import com.rackwatch.model.Reading;
import com.rackwatch.model.Room;
import com.rackwatch.model.Sensor;
import com.rackwatch.model.SensorKind;
import com.rackwatch.repository.ReadingRepository;
import com.rackwatch.repository.RoomRepository;
import com.rackwatch.repository.SensorRepository;
import com.rackwatch.web.dto.RoomStatusResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Room status is derived from the latest reading per sensor, not from
 * alerts: GREEN when every sensor has a reading inside the limits,
 * RED when any latest reading is outside them, AMBER when a sensor has
 * no reading yet. Alerts are listed separately and do not change this
 * status — a room with an acknowledged alert still shows its real,
 * current readings. */
@Service
public class RoomService {

    private final RoomRepository rooms;
    private final SensorRepository sensors;
    private final ReadingRepository readings;
    private final RackwatchProperties properties;

    public RoomService(
            RoomRepository rooms,
            SensorRepository sensors,
            ReadingRepository readings,
            RackwatchProperties properties) {
        this.rooms = rooms;
        this.sensors = sensors;
        this.readings = readings;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public List<RoomStatusResponse> listRooms() {
        List<RoomStatusResponse> result = new ArrayList<>();
        for (Room room : rooms.findAll()) {
            List<RoomStatusResponse.SensorStatus> sensorStatuses = new ArrayList<>();
            boolean anyOutOfRange = false;
            boolean anyMissing = false;
            for (Sensor sensor : sensors.findByRoomIdOrderById(room.getId())) {
                Optional<Reading> latest = readings.findFirstBySensorIdOrderByTsDesc(sensor.getId());
                boolean inRange = latest.map(reading -> inRange(sensor, reading)).orElse(false);
                if (latest.isEmpty()) {
                    anyMissing = true;
                } else if (!inRange) {
                    anyOutOfRange = true;
                }
                sensorStatuses.add(new RoomStatusResponse.SensorStatus(
                        sensor.getId(),
                        sensor.getKind(),
                        latest.map(Reading::getTempC).orElse(null),
                        latest.map(Reading::getRhPct).orElse(null),
                        latest.map(Reading::getTs).orElse(null),
                        latest.isPresent() && inRange));
            }
            String status = anyOutOfRange ? "RED" : anyMissing ? "AMBER" : "GREEN";
            result.add(new RoomStatusResponse(room.getId(), room.getName(), status, sensorStatuses));
        }
        return result;
    }

    private boolean inRange(Sensor sensor, Reading reading) {
        RackwatchProperties.Limits limits = properties.limits();
        if (sensor.getKind() == SensorKind.TEMP) {
            return reading.getTempC() != null
                    && reading.getTempC() >= limits.tempMinC()
                    && reading.getTempC() <= limits.tempMaxC();
        }
        return reading.getRhPct() != null
                && reading.getRhPct() >= limits.rhMinPct()
                && reading.getRhPct() <= limits.rhMaxPct();
    }
}
