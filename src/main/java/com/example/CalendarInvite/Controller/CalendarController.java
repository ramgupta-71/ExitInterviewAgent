package com.example.CalendarInvite.Controller;

import com.example.CalendarInvite.Service.GoogleCalendarService;
import com.example.CalendarInvite.Service.ZoomMeetingService;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.FreeBusyRequest;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import com.google.api.services.calendar.model.FreeBusyResponse;
import com.google.api.services.gmail.Gmail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    @Autowired
    ZoomMeetingService zoomMeetingService;

    @Autowired
    GoogleCalendarService googleCalendarService;

    @GetMapping("/freebusy")
    public String getFreeBusy(@RequestParam String email) throws Exception {
        Calendar service = GoogleCalendarService.getCalendarService();

        FreeBusyRequest freeBusyRequest = new FreeBusyRequest();
        freeBusyRequest.setTimeMin(new com.google.api.client.util.DateTime(System.currentTimeMillis()));
        freeBusyRequest.setTimeMax(new com.google.api.client.util.DateTime(System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000)); // 1 week ahead
        freeBusyRequest.setItems(Collections.singletonList(new FreeBusyRequestItem().setId(email)));

        FreeBusyResponse freeBusyResponse = service.freebusy().query(freeBusyRequest).execute();

        var busyTimes = freeBusyResponse.getCalendars().get(email).getBusy();

        if (busyTimes.isEmpty()) {
            return "User " + email + " is free for the entire time range.";
        }

        StringBuilder sb = new StringBuilder("User " + email + " busy times:\n");
        busyTimes.forEach(timeRange -> {
            sb.append("From: ").append(timeRange.getStart())
                    .append(" To: ").append(timeRange.getEnd())
                    .append("\n");
        });

        return sb.toString();
    }

    @PostMapping("/zoom/create")
    public Object createZoomMeeting(@RequestParam String startTime, @RequestParam String topic) {
        try {
            ZoomMeetingService zoomService = new ZoomMeetingService();
            ZoomMeetingService.MeetingDetails meeting = zoomService.createMeeting(startTime, topic);
            return meeting;  // will be serialized to JSON automatically by Spring Boot
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to create Zoom meeting: " + e.getMessage();
        }
    }

    @PostMapping("/sendInvite")
    public String sendCalendarInvite(@RequestParam String hostEmail, @RequestParam String attendeeEmail) {
        try {
            Calendar calendarService = GoogleCalendarService.getCalendarService();

            googleCalendarService.createMeetingEvent(calendarService, hostEmail, attendeeEmail);

            return "Invite sent to " + hostEmail + " and " + attendeeEmail;
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to send invite: " + e.getMessage();
        }
    }

    @PostMapping("/sendInvite")
    public String createGoogleCalEvent(@RequestParam String hostEmail, @RequestParam String attendeeEmail) {
        try {
            Calendar calendarService = GoogleCalendarService.getCalendarService();

            googleCalendarService.createMeetingEvent(calendarService, hostEmail, attendeeEmail);

            return "Invite sent to " + hostEmail + " and " + attendeeEmail;
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to send invite: " + e.getMessage();
        }
    }

}
