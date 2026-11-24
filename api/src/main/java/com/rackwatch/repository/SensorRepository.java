package com.rackwatch.repository;

import com.rackwatch.model.Sensor;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorRepository extends JpaRepository<Sensor, String> {

    List<Sensor> findByRoomIdOrderById(String roomId);
}
