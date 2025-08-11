package com.example.CalendarInvite.Service;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.gson.Gson;
import org.springframework.stereotype.Service;

@Service
public class ZoomMeetingService {

    static class ZoomCredentials {
        String accountId;
        String clientId;
        String clientSecret;
    }

    private static final String CREDENTIALS_FILE_PATH = "ZoomCredentials.json";

    private ZoomCredentials creds;

    private ZoomCredentials getCredentials() throws IOException {
        if (creds == null) {
            Gson gson = new Gson();
            try (InputStreamReader reader = new InputStreamReader(
                    getClass().getClassLoader().getResourceAsStream(CREDENTIALS_FILE_PATH))) {
                if (reader == null) {
                    throw new IOException(CREDENTIALS_FILE_PATH + " not found in classpath");
                }
                creds = gson.fromJson(reader, ZoomCredentials.class);
            }
        }
        return creds;
    }

    private String getAccessToken() throws IOException {
        ZoomCredentials creds = getCredentials(); // <-- Make sure creds are loaded here

        String tokenUrl = "https://zoom.us/oauth/token?grant_type=account_credentials&account_id=" + creds.accountId;
        HttpURLConnection tokenConn = (HttpURLConnection) new URL(tokenUrl).openConnection();
        tokenConn.setRequestMethod("POST");
        String auth = creds.clientId + ":" + creds.clientSecret;
        tokenConn.setRequestProperty("Authorization", "Basic " +
                Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));

        int responseCode = tokenConn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Failed to get access token, response code: " + responseCode);
        }

        try (BufferedReader br = new BufferedReader(new InputStreamReader(tokenConn.getInputStream()))) {
            StringBuilder tokenResponse = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) tokenResponse.append(line);
            String json = tokenResponse.toString();
            return json.split("\"access_token\":\"")[1].split("\"")[0];
        }
    }

    /**
     * Creates a Zoom meeting.
     * @param startTime UTC ISO string, e.g. "2025-08-11T15:00:00Z"
     * @param topic Meeting topic/title
     * @return MeetingDetails object containing join URL and meeting ID
     * @throws IOException if network or parsing fails
     */
    public MeetingDetails createMeeting(String startTime, String topic) throws IOException {
        String accessToken = getAccessToken();

        String userId = "me";  // Use "me" to create meeting for the authenticated user

        String meetingUrl = "https://api.zoom.us/v2/users/" + URLEncoder.encode(userId, StandardCharsets.UTF_8) + "/meetings";
        HttpURLConnection meetingConn = (HttpURLConnection) new URL(meetingUrl).openConnection();
        meetingConn.setRequestMethod("POST");
        meetingConn.setRequestProperty("Authorization", "Bearer " + accessToken);
        meetingConn.setRequestProperty("Content-Type", "application/json");
        meetingConn.setDoOutput(true);

        String body = "{"
                + "\"topic\":\"" + topic + "\","
                + "\"type\":2,"
                + "\"start_time\":\"" + startTime + "\","
                + "\"duration\":10080," // 7 days in minutes
                + "\"timezone\":\"UTC\""
                + "}";

        try (OutputStream os = meetingConn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = meetingConn.getResponseCode();
        if (responseCode != 201) {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(meetingConn.getErrorStream()))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) errorResponse.append(line);
                throw new IOException("Failed to create meeting, response code: " + responseCode + ", message: " + errorResponse);
            }
        }

        try (BufferedReader meetingReader = new BufferedReader(new InputStreamReader(meetingConn.getInputStream()))) {
            StringBuilder meetingResponse = new StringBuilder();
            String line;
            while ((line = meetingReader.readLine()) != null) meetingResponse.append(line);
            String json = meetingResponse.toString();

            String joinUrl = json.split("\"join_url\":\"")[1].split("\"")[0];
            String meetingId = json.split("\"id\":")[1].split(",")[0];

            return new MeetingDetails(joinUrl, meetingId);
        }
    }



    public static class MeetingDetails {
        public final String joinUrl;
        public final String meetingId;

        public MeetingDetails(String joinUrl, String meetingId) {
            this.joinUrl = joinUrl;
            this.meetingId = meetingId;
        }

        @Override
        public String toString() {
            return "MeetingDetails{" +
                    "joinUrl='" + joinUrl + '\'' +
                    ", meetingId='" + meetingId + '\'' +
                    '}';
        }
    }
}
