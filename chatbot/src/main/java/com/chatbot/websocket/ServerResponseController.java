package com.chatbot.websocket;

import com.chatbot.exception.GeolocationException;
import com.chatbot.exception.ReverseGeolocationException;
import com.chatbot.exception.WeatherAPIException;
import com.chatbot.geolocation.Geolocation;
import com.chatbot.service.RasaService;
import com.chatbot.service.TranslationService;
import com.chatbot.service.WeatherService;
import com.chatbot.websocket.responseMapperIntent.Intent;
import com.chatbot.websocket.responseMapperIntent.ResponseIntent;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

@Controller
public class ServerResponseController {
    private static final Logger logger = LoggerFactory.getLogger(ServerResponseController.class);
    private static final String WEATHER_INTENT = "weather";
    private static final String CITY_WEATHER_INTENT = "city_weather";
    private static final String OTHER_DAY_INTENT = "other_day";
    private static final String OTHER_CITY_INTENT = "other_city";
    private static final double CONFIDENCE_THRESHOLD = 0.9;
    private static final String ERROR_MESSAGE = "Entschuldigung ich konnte dich nicht wirklich verstehen.Versuche es nochmal und Achte auf deine Rechtschreibung!";
    private static final String RESPONSE_TEMPLATE = "Die aktuell vorausgesagte Temperatur für %s,%s am %s um %s Uhr beträgt %s Grad Celsius";
    private static final String RESPONSE_TEMPLATE_EN = "Die aktuell vorausgesagte Temperatur für %s,%s am %s um %s Uhr - beträgt %s Grad Celsius";
    private static final String LOCATION_TEMPLATE = "Aktuelle Position: %s %s %s , %s ";
    private static final TranslationService translationService = new TranslationService();
    private static final RasaService rasaService = new RasaService();
    private static final WeatherService weatherService = new WeatherService();
    private static final String[] intentNames = {WEATHER_INTENT, CITY_WEATHER_INTENT, OTHER_DAY_INTENT, OTHER_CITY_INTENT};

    String city;
    String countryCode;
    String requestedDay;
    String hour;
    String temperature;
    String currentLang = "de";
    Intent lastIntent;

    @MessageMapping("/inquiry")
    @SendToUser("/topic/weather")
    public ServerResponse serverResponse(ClientPrompt prompt, @Header("simpSessionId") String sessionId) {
        MDC.put("sessionId", sessionId);
        ResponseIntent mappedResponse = rasaService.getInitialParameters(HtmlUtils.htmlEscape(prompt.getText()));
        try {
            mapToSlots(mappedResponse);
        } catch (GeolocationException | ReverseGeolocationException | IndexOutOfBoundsException | NullPointerException e) {
            return createErrorResponse();
        }

        city = StringUtils.capitalize(weatherService.getCity()
                .replace("&uuml;", "ü")
                .replace("&Auml;", "ä")
                .replace("&ouml;", "ö"));
        try {
            countryCode = weatherService.getCountryCode().toUpperCase();
            requestedDay = weatherService.getDay();
            hour = weatherService.getHour();

            switch (mappedResponse.getIntent().getName()) {
                case WEATHER_INTENT ->
                        temperature = String.valueOf(weatherService.cityWeatherApiCall(true).getTemperature());
                case CITY_WEATHER_INTENT ->
                        temperature = String.valueOf(weatherService.cityWeatherApiCall(false).getTemperature());
                case OTHER_DAY_INTENT, OTHER_CITY_INTENT -> {
                    if (Objects.equals(lastIntent.getName(), WEATHER_INTENT)) {
                        temperature = String.valueOf(weatherService.cityWeatherApiCall(true).getTemperature());
                        break;
                    } else if (Objects.equals(lastIntent.getName(), CITY_WEATHER_INTENT)) {
                        temperature = String.valueOf(weatherService.cityWeatherApiCall(false).getTemperature());
                        break;
                    }
                    return createErrorResponse();
                }
                default -> {
                    if (mappedResponse.getEntities().length != 0 || mappedResponse.getIntent().getConfidence() < CONFIDENCE_THRESHOLD) {
                        return createErrorResponse();
                    }
                    //Request zurück an Rasa für die standard Chatbot Antwort und den Intent der Nachricht für die nächste Nachricht setzen
                    lastIntent = mappedResponse.getIntent();
                    return new ServerResponse(translateMessageIfNeeded(rasaService.getChatResponse(HtmlUtils.htmlEscape(prompt.getText()))));
                }
            }
        } catch (DateTimeParseException | WeatherAPIException | NullPointerException | IndexOutOfBoundsException e) {
            e.printStackTrace();
            return createErrorResponse();
        }

        //Intent für die nächste Nachricht setzen
        lastIntent = mappedResponse.getIntent();
        String response = String.format(RESPONSE_TEMPLATE, city, countryCode, requestedDay, hour, temperature);

        /*Es muss geprüft werden, ob die Sprache aktuell auf Englisch gestellt,
        * wenn das der Fall ist, wird mithilfe des TranslationService übersetzt*/
        if (Objects.equals(currentLang, "gb")) {
            String responseEn = String.format(RESPONSE_TEMPLATE_EN, city, countryCode, requestedDay, hour, temperature);
            response = translationService.translateLongMessage(responseEn);
        }
        return new ServerResponse(response);
    }

    @MessageMapping("/lat")
    public void getLat(String lat,  @Header("simpSessionId") String sessionId) {
        MDC.put("sessionId", sessionId);
        logger.info("Lat received from FE: {}", lat);
        weatherService.setCurrentLat(lat);
        try {
            weatherService.getReverseGeolocation();
        } catch (ReverseGeolocationException e) {
            e.printStackTrace();
        }

    }

    @MessageMapping("/lon")
    @SendToUser("/topic/currentLoc")
    public ServerResponse getLon(String lon,  @Header("simpSessionId") String sessionId) {
        MDC.put("sessionId", sessionId);
        logger.info("Lon received from FE: {}", lon);
        weatherService.setCurrentLon(lon);
        try {
            weatherService.getReverseGeolocation();
        } catch (ReverseGeolocationException e) {
            e.printStackTrace();
        }
        String currentLocation = String.format(LOCATION_TEMPLATE, weatherService.getCity(), weatherService.getStreet(), weatherService.getHouseNumber(), weatherService.getCountry());
        return new ServerResponse(translateMessageIfNeeded(currentLocation));
    }

    @MessageMapping("/icon")
    @SendToUser("/topic/icon")
    public ServerResponse sendIcon( @Header("simpSessionId") String sessionId ) {
        MDC.put("sessionId", sessionId);
        try{
        if (List.of(intentNames).contains(rasaService.getLatestMessage().getIntent().getName())) {
            return new ServerResponse(weatherService.getWeatherIcon());
        }
        }catch(NullPointerException e){
            logger.error("There was no latest message to be aquired!");
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @MessageMapping("/lang")
    public void changeLanguage(String language,  @Header("simpSessionId") String sessionId) {
        MDC.put("sessionId", sessionId);
        currentLang = language;
        logger.info("Language after Language change: {}", currentLang);
    }

    public ServerResponse createErrorResponse() {
        weatherService.setWeatherIcon(null);
        return new ServerResponse(translateMessageIfNeeded(ERROR_MESSAGE));
    }

    public void mapToSlots(ResponseIntent response) throws GeolocationException, ReverseGeolocationException, NullPointerException {
        if (Objects.equals(response.getIntent().getName(), CITY_WEATHER_INTENT)) {
            Geolocation geolocation = weatherService.getGeolocation(response.getEntities()[0].getValue().replace("?", ""));
            logger.info("Mapped lon before: {}", weatherService.getLon());
            weatherService.setLat(String.valueOf(geolocation.getLatitude()));
            weatherService.setLon(String.valueOf(geolocation.getLongitude()));
            logger.info("Mapped lat after: {}", weatherService.getLon());
            weatherService.setCity(response.getEntities()[0].getValue().replace("?", ""));
            weatherService.setDay(response.getEntities()[1].getValue());
        }
        if (Objects.equals(response.getIntent().getName(), WEATHER_INTENT)) {
            weatherService.setDay(response.getEntities()[0].getValue());
            weatherService.getReverseGeolocation();
        }

        if (Objects.equals(response.getIntent().getName(), OTHER_DAY_INTENT)) {
            weatherService.setDay(response.getEntities()[0].getValue());
        }

        if (Objects.equals(response.getIntent().getName(), OTHER_CITY_INTENT)) {
            Geolocation geolocation = weatherService.getGeolocation(response.getEntities()[0].getValue().replace("?", ""));
            logger.info("Mapped lon before: {}", weatherService.getLon());
            weatherService.setLat(String.valueOf(geolocation.getLatitude()));
            weatherService.setLon(String.valueOf(geolocation.getLongitude()));
            logger.info("Mapped lon after: {}", weatherService.getLon());
            weatherService.setCity(response.getEntities()[0].getValue().replace("?", ""));
        }
    }

    public String translateMessageIfNeeded(String toTranslate) {
        //Mehrere Sprachen mit Switch case möglich, aber prinzipiell ersetzbar durch if Statement.
        return switch (currentLang) {
            case "gb" -> translationService.translate(toTranslate);
            default -> toTranslate;
        };
    }
}

