package by.gdev.alert.job.parser.scheduller.parser;

import by.gdev.alert.job.parser.scheduller.parser.properties.ParserScheduleProperties;
import by.gdev.alert.job.parser.service.order.SiteParser;
import by.gdev.common.model.OrderDTO;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;

import java.util.Date;
import java.util.List;

@Slf4j
public class SiteParserScheduler {

    private final SiteParser parser;
    private final OrderDispatcher dispatcher;


    public SiteParserScheduler(SiteParser parser,
                               OrderDispatcher dispatcher,
                               ParserScheduleProperties props,
                               TaskScheduler taskScheduler) {
        this.parser = parser;
        this.dispatcher = dispatcher;

        // регистрируем задачу прямо в конструкторе
        if (parser.isActive()){
            taskScheduler.scheduleWithFixedDelay(
                    this::runParser,
                    new Date(System.currentTimeMillis() + props.getInitialDelayMillis()),
                    props.getFixedDelayMillis());

            log.debug("Шедулер для {} запущен: initialDelay={}s, fixedDelay={}s",
                    parser.getSiteName(), props.getInitialDelaySeconds(), props.getFixedDelaySeconds());
        }
        else log.debug("Парсер для {} ВЫКЛЮЧЕН..", parser.getSiteName());

    }

    private void runParser() {
        log.debug("Запуск парсера {} в потоке {}", parser.getSiteName(), Thread.currentThread().getName());
        try {
            long startTime = System.currentTimeMillis();
            List<OrderDTO> orders = parser.parse();
            long duration = System.currentTimeMillis() - startTime;
            String formattedDuration = formatDuration(duration);
            dispatcher.dispatch(orders, parser.getSiteName());
            log.debug("Парсер {} завершил работу за {}, найдено {} заказов",
                    parser.getSiteName(), formattedDuration, orders.size());
        }
        catch (PlaywrightException e) {
            if (isExpectedPlaywrightFailure(e.getMessage())) {
                log.debug("Playwright (сеть/прокси/таймаут) для парсера {}: {}",
                        parser.getSiteName(), e.getMessage());
                return;
            }
            log.error("Playwright ошибка парсера {} : {}", parser.getSiteName(), e.getMessage());
        }
        catch (Exception e) {
            PlaywrightException playwright = unwrapPlaywrightException(e);
            if (playwright != null && isExpectedPlaywrightFailure(playwright.getMessage())) {
                log.debug("Playwright (сеть/прокси/таймаут) для парсера {}: {}",
                        parser.getSiteName(), playwright.getMessage());
                return;
            }
            log.error("Ошибка парсера {}: {}", parser.getSiteName(), e.getMessage(), e);
        }
    }

    private static boolean isExpectedPlaywrightFailure(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("Timeout")
                || message.contains("TimeoutError")
                || message.contains("ERR_TUNNEL_CONNECTION_FAILED")
                || message.contains("ERR_PROXY_CONNECTION_FAILED");
    }

    private static PlaywrightException unwrapPlaywrightException(Throwable e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof PlaywrightException pe) {
                return pe;
            }
            current = current.getCause();
        }
        return null;
    }

    private String formatDuration(long millis) {
        if (millis < 1000) {
            return millis + " мс";
        } else if (millis < 60000) {
            return String.format("%.2f сек", millis / 1000.0);
        } else {
            long minutes = millis / 60000;
            long seconds = (millis % 60000) / 1000;
            return String.format("%d мин %d сек", minutes, seconds);
        }
    }
}