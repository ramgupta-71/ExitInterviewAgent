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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
    public String findCommonFreeTime(
            @RequestParam List<String> emails,
            @RequestParam String lastDayOfWork // e.g. "08/12/2025" or "2025-08-12"
    ) throws Exception {
        Calendar service = GoogleCalendarService.getCalendarService();

        // ---- Config ----
        ZoneId tz = ZoneId.of("America/Chicago");
        LocalTime workStart = LocalTime.of(9, 0);
        LocalTime workEnd   = LocalTime.of(17, 0);
        int minMinutes = 30;

        // ---- Parse last day of work ----
        LocalDate lastDay;
        try {
            // Accept both MM/dd/yyyy and ISO yyyy-MM-dd
            DateTimeFormatter mdy = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            if (lastDayOfWork.contains("/")) lastDay = LocalDate.parse(lastDayOfWork, mdy);
            else lastDay = LocalDate.parse(lastDayOfWork); // ISO
        } catch (Exception ex) {
            return "Invalid lastDayOfWork format. Use MM/dd/yyyy or yyyy-MM-dd.";
        }

        // ---- Build search window: [tomorrow 9:00, min(tomorrow+7d 17:00, lastDay 17:00)] ----
        ZonedDateTime now = ZonedDateTime.now(tz);
        ZonedDateTime startOfWindow = now.plusDays(1).withHour(workStart.getHour()).withMinute(workStart.getMinute()).withSecond(0).withNano(0);
        ZonedDateTime sevenDayCap = startOfWindow.plusDays(7).withHour(workEnd.getHour()).withMinute(workEnd.getMinute()).withSecond(0).withNano(0);
        ZonedDateTime lastDayEnd = lastDay.atTime(workEnd).atZone(tz);
        ZonedDateTime endOfWindow = sevenDayCap.isBefore(lastDayEnd) ? sevenDayCap : lastDayEnd;

        if (!startOfWindow.isBefore(endOfWindow)) {
            return "No common free time between tomorrow and the last day of work.";
        }

        // ---- Free/Busy query over the window ----
        FreeBusyRequest freeBusyRequest = new FreeBusyRequest()
                .setTimeMin(new com.google.api.client.util.DateTime(startOfWindow.toInstant().toEpochMilli()))
                .setTimeMax(new com.google.api.client.util.DateTime(endOfWindow.toInstant().toEpochMilli()))
                .setItems(emails.stream()
                        .map(email -> new FreeBusyRequestItem().setId(email))
                        .collect(Collectors.toList()));

        FreeBusyResponse freeBusyResponse = service.freebusy().query(freeBusyRequest).execute();

        // ---- Merge, normalize and sort busy periods ----
        List<TimePeriod> mergedBusyTimes = freeBusyResponse.getCalendars().entrySet().stream()
                .flatMap(e -> {
                    List<TimePeriod> b = e.getValue().getBusy();
                    return (b == null ? Collections.<TimePeriod>emptyList() : b).stream();
                })
                .sorted(Comparator.comparing(tp -> tp.getStart().getValue()))
                .collect(Collectors.toList());

        // Helper: clamp a time to business hours for its day
        java.util.function.UnaryOperator<ZonedDateTime> clampToWorkHours = (zdt) -> {
            LocalTime lt = zdt.toLocalTime();
            if (lt.isBefore(workStart)) {
                return zdt.withHour(workStart.getHour()).withMinute(workStart.getMinute()).withSecond(0).withNano(0);
            }
            if (!lt.isBefore(workEnd)) {
                // at/after 5pm -> next day 9am
                return zdt.plusDays(1).withHour(workStart.getHour()).withMinute(workStart.getMinute()).withSecond(0).withNano(0);
            }
            return zdt.withSecond(0).withNano(0);
        };

        ZonedDateTime currentTime = clampToWorkHours.apply(startOfWindow);

        for (TimePeriod busy : mergedBusyTimes) {
            ZonedDateTime busyStart = ZonedDateTime.parse(busy.getStart().toStringRfc3339()).withZoneSameInstant(tz);
            ZonedDateTime busyEnd   = ZonedDateTime.parse(busy.getEnd().toStringRfc3339()).withZoneSameInstant(tz);

            // Ignore busy blocks outside our window
            if (busyEnd.isBefore(startOfWindow)) continue;
            if (busyStart.isAfter(endOfWindow)) break;

            // Check for a gap before this busy block
            ZonedDateTime endOfWorkToday = currentTime.withHour(workEnd.getHour()).withMinute(workEnd.getMinute()).withSecond(0).withNano(0);
            ZonedDateTime candidateEnd = currentTime.plusMinutes(minMinutes);

            if (candidateEnd.isBefore(busyStart)
                    && !candidateEnd.isAfter(endOfWorkToday)
                    && !candidateEnd.isAfter(endOfWindow)) {
                return currentTime.toString(); // ISO-8601
            }

            // Advance currentTime past this busy block if needed
            if (busyEnd.isAfter(currentTime)) {
                currentTime = busyEnd;
            }

            // If past business hours, move to next day 9am
            if (!currentTime.toLocalTime().isBefore(workEnd)) {
                currentTime = currentTime.plusDays(1)
                        .withHour(workStart.getHour()).withMinute(workStart.getMinute()).withSecond(0).withNano(0);
            }

            // If we advanced before 9am, clamp up to 9am
            currentTime = clampToWorkHours.apply(currentTime);

            // If we rolled beyond the window, stop
            if (!currentTime.isBefore(endOfWindow)) break;
        }

        // Tail check after the last busy block within window
        currentTime = clampToWorkHours.apply(currentTime);
        if (currentTime.isBefore(endOfWindow)) {
            ZonedDateTime endOfWorkToday = currentTime.withHour(workEnd.getHour()).withMinute(workEnd.getMinute()).withSecond(0).withNano(0);
            ZonedDateTime candidateEnd = currentTime.plusMinutes(minMinutes);
            if (!candidateEnd.isAfter(endOfWorkToday) && !candidateEnd.isAfter(endOfWindow)) {
                return currentTime.toString();
            }
        }

        return "No common free time found between tomorrow and the last day of work within working hours.";
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


//    @PostMapping("/sendInvite")
//    public String createGoogleCalEvent(@RequestParam String hostEmail, @RequestParam String attendeeEmail, @RequestParam String prompt) {
//        try {
//            // List of emails for finding common free time
//            List<String> emails = Arrays.asList(hostEmail, attendeeEmail);
//
//            // Call findCommonFreeTime and get the start time string (ISO-8601)
//            // startTimeStr = findCommonFreeTime(emails);
//
//            if (startTimeStr.startsWith("No common free time")) {
//                return startTimeStr; // no free slot found
//            }
//
//            // Parse start time string to ZonedDateTime
//            ZonedDateTime startTime = ZonedDateTime.parse(startTimeStr);
//
//            // Calculate end time as start + 1 hour
//            ZonedDateTime endTime = startTime.plusHours(1);
//
//            // Create Zoom meeting with start time and a topic (e.g., "Meeting with attendeeEmail")
//            ZoomMeetingService zoomService = new ZoomMeetingService();
//            ZoomMeetingService.MeetingDetails meetingDetails = zoomService.createMeeting(startTimeStr, "Meeting with " + attendeeEmail);
//
//            // Get Zoom join URL from meeting details
//            String zoomJoinUrl = meetingDetails.getJoinUrl();
//
//            Calendar calendarService = GoogleCalendarService.getCalendarService();
//
//            String AiText= String.valueOf(generateResponse(prompt));
//
//            // Pass Zoom join URL to calendar event description
//            googleCalendarService.createMeetingEvent(calendarService, hostEmail, attendeeEmail, startTime, endTime, zoomJoinUrl,AiText);
//
//            return "Invite sent to " + hostEmail + " and " + attendeeEmail + " starting at " + startTimeStr;
//        } catch (Exception e) {
//            e.printStackTrace();
//            return "Failed to send invite: " + e.getMessage();
//        }
//    }

    @PostMapping("/sendInvite2")
    public String createGoogleCalEventV2(
            @RequestParam String hostEmail,
            @RequestParam List<String> attendeeEmails, // accept multiple
            @RequestParam String prompt,
            @RequestParam @DateTimeFormat(pattern = "MM/dd/yyyy") LocalDate lastDayOfWork    ) {
        try {
            // Include host + all attendees
            List<String> emails = new ArrayList<>();
            emails.add(hostEmail);
            emails.addAll(attendeeEmails);

            // Parse last day of work into a date or ZonedDateTime

            // Pass last day into your method
            String startTimeStr = findCommonFreeTime(emails, String.valueOf(lastDayOfWork));
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
