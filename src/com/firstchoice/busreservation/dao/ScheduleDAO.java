package com.firstchoice.busreservation.dao;

import com.firstchoice.busreservation.model.Schedule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduleDAO {
    int nextId();
    void save(Schedule schedule);
    Optional<Schedule> findById(int id);
    List<Schedule> search(String source, String destination, LocalDate date);
    List<Schedule> findAll();
}
