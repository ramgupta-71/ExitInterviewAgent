package com.example.CalendarInvite.Controller;

import com.example.CalendarInvite.Service.GoogleCalendarService;
import com.example.CalendarInvite.Service.ZoomMeetingService;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.*;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

    @PostMapping("/findCommonFreeTime")
    public String findCommonFreeTime(@RequestParam List<String> emails) throws Exception {
        Calendar service = GoogleCalendarService.getCalendarService();

        // Define working hours range for search
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/Chicago"));
        ZonedDateTime startOfSearch = now.withHour(9).withMinute(0).withSecond(0);
        ZonedDateTime endOfSearch = now.withHour(17).withMinute(0).withSecond(0);

        FreeBusyRequest freeBusyRequest = new FreeBusyRequest()
                .setTimeMin(new com.google.api.client.util.DateTime(startOfSearch.toInstant().toEpochMilli()))
                .setTimeMax(new com.google.api.client.util.DateTime(endOfSearch.plusDays(7).toInstant().toEpochMilli())) // 7 days search window
                .setItems(emails.stream()
                        .map(email -> new FreeBusyRequestItem().setId(email))
                        .collect(Collectors.toList()));

        FreeBusyResponse freeBusyResponse = service.freebusy().query(freeBusyRequest).execute();

        // Convert Map<String, FreeBusyCalendar> to Map<String, List<TimePeriod>>
        Map<String, List<TimePeriod>> busyTimesMap = new HashMap<>();
        for (Map.Entry<String, FreeBusyCalendar> entry : freeBusyResponse.getCalendars().entrySet()) {
            busyTimesMap.put(entry.getKey(), entry.getValue().getBusy());
        }
        List<TimePeriod> mergedBusyTimes = busyTimesMap.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparing(tp -> tp.getStart().getValue()))
                .toList();

        // Find first free slot within 9–5
        ZonedDateTime currentTime = startOfSearch;
        for (TimePeriod busy : mergedBusyTimes) {
            ZonedDateTime busyStart = ZonedDateTime.parse(busy.getStart().toStringRfc3339());
            ZonedDateTime busyEnd = ZonedDateTime.parse(busy.getEnd().toStringRfc3339());

            if (currentTime.plusMinutes(30).isBefore(busyStart)) {
                // Found a gap of at least 30 minutes — return just the start time
                return currentTime.toString();
            }

            if (busyEnd.isAfter(currentTime)) {
                currentTime = busyEnd;
            }
        }

        // Check if free time is available at the end of the day
        if (currentTime.plusMinutes(30).isBefore(endOfSearch)) {
            return currentTime.toString();
        }

        return "No common free time found within working hours.";
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

    @PostMapping("/sendInviteV1")
    public String sendCalendarInvite(@RequestParam String hostEmail, @RequestParam String attendeeEmail) {
        try {
            Calendar calendarService = GoogleCalendarService.getCalendarService();

            //googleCalendarService.createMeetingEvent(calendarService, hostEmail, attendeeEmail);

            return "Invite sent to " + hostEmail + " and " + attendeeEmail;
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to send invite: " + e.getMessage();
        }
    }


    @Value("${GOOGLE_API_KEY}")
    private String googleApiKey;




    @PostMapping("/generate")
    public @Nullable String generateResponse(@RequestBody String prompt) {
            Client client = Client.builder().apiKey(googleApiKey).build();

            GenerateContentResponse response = client.models.generateContent(
                    "gemini-2.5-flash",
                    prompt,
                    null
            );

            return response.text();
    }


    @PostMapping("/sendInvite")
    public String createGoogleCalEvent(@RequestParam String hostEmail, @RequestParam String attendeeEmail, @RequestParam String prompt) {
        try {
            // List of emails for finding common free time
            List<String> emails = Arrays.asList(hostEmail, attendeeEmail);

            // Call findCommonFreeTime and get the start time string (ISO-8601)
            String startTimeStr = findCommonFreeTime(emails);

            if (startTimeStr.startsWith("No common free time")) {
                return startTimeStr; // no free slot found
            }

            // Parse start time string to ZonedDateTime
            ZonedDateTime startTime = ZonedDateTime.parse(startTimeStr);

            // Calculate end time as start + 1 hour
            ZonedDateTime endTime = startTime.plusHours(1);

            // Create Zoom meeting with start time and a topic (e.g., "Meeting with attendeeEmail")
            ZoomMeetingService zoomService = new ZoomMeetingService();
            ZoomMeetingService.MeetingDetails meetingDetails = zoomService.createMeeting(startTimeStr, "Meeting with " + attendeeEmail);

            // Get Zoom join URL from meeting details
            String zoomJoinUrl = meetingDetails.getJoinUrl();

            Calendar calendarService = GoogleCalendarService.getCalendarService();

            String AiText= String.valueOf(generateResponse(prompt));

            // Pass Zoom join URL to calendar event description
            googleCalendarService.createMeetingEvent(calendarService, hostEmail, attendeeEmail, startTime, endTime, zoomJoinUrl,AiText);

            return "Invite sent to " + hostEmail + " and " + attendeeEmail + " starting at " + startTimeStr;
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to send invite: " + e.getMessage();
        }
    }

    @PostMapping("/sendInvite2")
    public String createGoogleCalEventV2(
            @RequestParam String hostEmail,
            @RequestParam List<String> attendeeEmails, // accept multiple
            @RequestParam String prompt) {
        try {
            // Include host + all attendees
            List<String> emails = new ArrayList<>();
            emails.add(hostEmail);
            emails.addAll(attendeeEmails);

            // Find common free time
            String startTimeStr = findCommonFreeTime(emails);
            if (startTimeStr.startsWith("No common free time")) {
                return startTimeStr;
            }

            ZonedDateTime startTime = ZonedDateTime.parse(startTimeStr);
            ZonedDateTime endTime = startTime.plusHours(1);

            // Create Zoom meeting
            ZoomMeetingService zoomService = new ZoomMeetingService();
            ZoomMeetingService.MeetingDetails meetingDetails =
                    zoomService.createMeeting(startTimeStr, "Meeting with attendees");
            String zoomJoinUrl = meetingDetails.getJoinUrl();

            // Calendar service
            Calendar calendarService = GoogleCalendarService.getCalendarService();
            String AiText = String.valueOf(generateResponse(prompt));

            // Pass the list of attendees to your calendar creation method
            googleCalendarService.createMeetingEventV2(
                    calendarService,
                    hostEmail,
                    attendeeEmails,  // list instead of single email
                    startTime,
                    endTime,
                    zoomJoinUrl,
                    AiText
            );

            return "Invite sent to " + String.join(", ", emails) + " starting at " + startTimeStr;

        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to send invite: " + e.getMessage();
        }
    }






}
