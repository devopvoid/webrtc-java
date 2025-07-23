# Logging

This guide explains how to use the logging capabilities. The library provides native WebRTC logging through the `dev.onvoid.webrtc.logging.Logging` class.

## Native WebRTC Logging

The `Logging` class provides access to the native WebRTC logging system, allowing you to:
- Log messages at different severity levels
- Configure logging behavior
- Implement custom log sinks to capture and process log messages

### Severity Levels

The `Logging` class defines the following severity levels (in order of increasing severity):

| Level | Description |
|-------|-------------|
| `VERBOSE` | For data which should not appear in the normal debug log, but should appear in diagnostic logs |
| `INFO` | Used in debugging |
| `WARNING` | Something that may warrant investigation |
| `ERROR` | A critical error has occurred |
| `NONE` | Do not log |

### Basic Logging

The `Logging` class provides several methods for logging messages:

```java
import dev.onvoid.webrtc.logging.Logging;

// Log messages with different severity levels
Logging.verbose("Detailed information for diagnostic purposes");
Logging.info("General information about application operation");
Logging.warn("Potential issue that might need attention");
Logging.error("Critical error that affects application operation");

// Log an error with exception details
try {
    // Some operation that might throw an exception
} catch (Exception e) {
    Logging.error("Failed to perform operation", e);
}
```

### Configuring Logging Behavior

You can configure various aspects of the logging system:

```java
// Configure logging to debug output with minimum severity level
Logging.logToDebug(Logging.Severity.INFO);

// Enable/disable thread ID in log messages
Logging.logThreads(true);

// Enable/disable timestamps in log messages
Logging.logTimestamps(true);
```

### Custom Log Sinks

You can implement custom log handlers by creating a class that implements the `LogSink` interface:

```java
import dev.onvoid.webrtc.logging.LogSink;
import dev.onvoid.webrtc.logging.Logging;
import dev.onvoid.webrtc.logging.Logging.Severity;

public class CustomLogSink implements LogSink {
    
    @Override
    public void onLogMessage(Severity severity, String message) {
        // Process log messages as needed
        // For example, write to a file, send to a server, or display in UI
        System.out.println("[" + severity + "] " + message);
    }
}
```

Register your custom log sink to receive log messages:

```java
// Create and register a log sink for messages with INFO severity or higher
CustomLogSink logSink = new CustomLogSink();
Logging.addLogSink(Logging.Severity.INFO, logSink);
```

The log sink will receive all log messages with a severity level equal to or higher than the specified minimum level.

## Integration with Other Logging Frameworks

If you're using a different logging framework like Log4j or SLF4J, you can create a bridge by implementing a custom `LogSink` that forwards messages to your preferred logging system:

```java
import dev.onvoid.webrtc.logging.LogSink;
import dev.onvoid.webrtc.logging.Logging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slf4jLogSink implements LogSink {
    
    private static final Logger logger = LoggerFactory.getLogger("WebRTC");
    
    @Override
    public void onLogMessage(Logging.Severity severity, String message) {
        switch (severity) {
            case VERBOSE:
                logger.trace(message);
                break;
            case INFO:
                logger.info(message);
                break;
            case WARNING:
                logger.warn(message);
                break;
            case ERROR:
                logger.error(message);
                break;
            default:
                // Do nothing for NONE
        }
    }
}
```

Then register this sink with the WebRTC logging system:

```java
Logging.addLogSink(Logging.Severity.VERBOSE, new Slf4jLogSink());
```

This approach allows you to integrate WebRTC's native logging with your application's existing logging infrastructure.

---

## Conclusion
By using the provided methods and custom log sinks, you can effectively capture, process, and manage log messages to aid in debugging and monitoring your application. Whether you choose to use the native logging capabilities or integrate with existing logging frameworks, the WebRTC logging system is designed to be adaptable to your needs.