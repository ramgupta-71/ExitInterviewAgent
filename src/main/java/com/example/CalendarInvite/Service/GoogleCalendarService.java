package com.example.CalendarInvite.Service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Message;
import org.springframework.stereotype.Service;


import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

@Service
public class GoogleCalendarService {

    private static final String APPLICATION_NAME = "CalendarInviteApp";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    private static final List<String> SCOPES = Arrays.asList(CalendarScopes.CALENDAR, GmailScopes.GMAIL_SEND);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    public static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws Exception {
        InputStream in = GoogleCalendarService.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new RuntimeException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(Paths.get(TOKENS_DIRECTORY_PATH).toFile()))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(8888)
                .setCallbackPath("/Callback")
                .build();

        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    public static Calendar getCalendarService() throws Exception {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = getCredentials(HTTP_TRANSPORT);
        return new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    public static Gmail getGmailService() throws Exception {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = getCredentials(HTTP_TRANSPORT);
        return new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    public void createMeetingEvent(Calendar calendarService, String hostEmail, String attendeeEmail) throws Exception {
        Event event = new Event()
                .setSummary("Meeting")
                .setLocation("Online")
                .setDescription("Scheduled meeting between host and attendee.");

        EventDateTime start = new EventDateTime()
                .setDateTime(new com.google.api.client.util.DateTime("2025-08-12T10:00:00-05:00"))
                .setTimeZone("America/Chicago");
        event.setStart(start);

        EventDateTime end = new EventDateTime()
                .setDateTime(new com.google.api.client.util.DateTime("2025-08-12T11:00:00-05:00"))
                .setTimeZone("America/Chicago");
        event.setEnd(end);

        event.setAttendees(Arrays.asList(
                new EventAttendee().setEmail(hostEmail),
                new EventAttendee().setEmail(attendeeEmail)
        ));

        Event createdEvent = calendarService.events()
                .insert("primary", event)
                .setSendUpdates("all") // sends calendar invites by email
                .execute();

        System.out.printf("Event created: %s\n", createdEvent.getHtmlLink());
    }


    private void sendEmail(Gmail service, String from, String to, String subject, String bodyText) throws IOException {
        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);

        try {
            MimeMessage email = new MimeMessage(session);
            email.setFrom(new InternetAddress(from));
            email.addRecipient(javax.mail.Message.RecipientType.TO, new InternetAddress(to));
            email.setSubject(subject);
            email.setText(bodyText);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            email.writeTo(buffer);
            byte[] rawMessageBytes = buffer.toByteArray();
            String encodedEmail = Base64.getUrlEncoder().encodeToString(rawMessageBytes);

            Message message = new Message();
            message.setRaw(encodedEmail);

            service.users().messages().send("me", message).execute();
        } catch (Exception e) {
            throw new IOException("Failed to send email: " + e.getMessage(), e);
        }
    }
}
