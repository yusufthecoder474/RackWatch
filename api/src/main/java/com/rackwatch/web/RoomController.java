package com.rackwatch.web;

import com.rackwatch.service.RoomService;
import com.rackwatch.web.dto.RoomStatusResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/rooms — latest value and status per room. */
@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping
    public List<RoomStatusResponse> listRooms() {
        return rooms.listRooms();
    }
}
