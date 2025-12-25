package com.testinsight.aihealer;

import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import lombok.Getter;
import lombok.Setter;
import org.openqa.selenium.*;
import org.openqa.selenium.remote.UnreachableBrowserException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;
import static com.testinsight.aihealer.ExceptionHandler.Severity.*;

/**
 * Comprehensive Exception Handler for Appium + Java
 * Compatible with Selenium 3.x and 4.x versions
 * Handles all types of locator and WebDriver exceptions with detailed logging and recovery strategies
 * 
 * Integrated with AI Healer Service for automatic locator healing
 *
 * @author BITAP Mobile Automation Team
 * @version 4.0 - Standalone with AI Healing
 */
public class ExceptionHandler {

    private static final Logger logger = Logger.getLogger(ExceptionHandler.class.getName());
    private int retryCount;
    
    // ANSI Color codes for console output
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET = "\u001B[0m";
    
    // Driver instance
    private final AppiumDriver driver;
    
    // Optional callbacks
    private ScreenshotCallback screenshotCallback;
    private TestFailureCallback testFailureCallback;
    
    // Store last exception result for reference
    @Getter
    private ExceptionResult lastExceptionResult;
    
    // Getters and setters
    @Setter
    @Getter
    private int maxRetries;
    @Setter
    @Getter
    private boolean takeScreenshotOnFailure;
    @Setter
    @Getter
    private String screenshotPath;

    /**
     * Constructor with AppiumDriver
     */
    public ExceptionHandler(AppiumDriver driver) {
        this.driver = driver;
        this.maxRetries = 1;
        this.takeScreenshotOnFailure = true;
        this.screenshotPath = null;
        this.retryCount = 0;
    }
    
    /**
     * Constructor with AppiumDriver and configuration
     */
    public ExceptionHandler(AppiumDriver driver, int maxRetries, boolean takeScreenshotOnFailure) {
        this.driver = driver;
        this.maxRetries = maxRetries;
        this.takeScreenshotOnFailure = takeScreenshotOnFailure;
        this.screenshotPath = null;
        this.retryCount = 0;
    }
    
    /**
     * Set screenshot callback for custom screenshot handling
     */
    public void setScreenshotCallback(ScreenshotCallback callback) {
        this.screenshotCallback = callback;
    }
    
    /**
     * Set test failure callback for custom test failure handling
     */
    public void setTestFailureCallback(TestFailureCallback callback) {
        this.testFailureCallback = callback;
    }
    
    /**
     * Callback interface for screenshot capture
     */
    public interface ScreenshotCallback {
        String captureScreenshot();
    }
    
    /**
     * Callback interface for test failure handling
     */
    public interface TestFailureCallback {
        void markTestAsFailed(String message, Exception exception);
    }
    
    /**
     * Capture screenshot using callback if enabled
     */
    private String captureScreenshotIfEnabled() {
        if (takeScreenshotOnFailure && screenshotCallback != null) {
            try {
                String path = screenshotCallback.captureScreenshot();
                this.screenshotPath = path;
                return path;
            } catch (Exception e) {
                logger.warning("Failed to capture screenshot: " + e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * Mark test as failed using callback
     */
    private void markTestFailed(String message, Exception exception) {
        if (testFailureCallback != null) {
            testFailureCallback.markTestAsFailed(message, exception);
        } else {
            // Default: just log if no callback provided
            logger.severe("TEST FAILED: " + message);
        }
    }

    /**
     * Main exception handling method - handles all Appium/Selenium exceptions
     * 
     * New Features (Enhanced):
     * - AI-powered locator healing for recoverable exceptions
     * - Automatic retry with healed locators
     * - Returns WebElement when healing succeeds
     * - Fails test when healing fails or exception is non-recoverable
     * - Stores ExceptionResult in class variable for reference
     * 
     * Flow:
     * 1. Catches and categorizes the exception
     * 2. If recoverable with locator → attempts AI healing
     * 3. Retries with healed locator (up to 3 alternatives)
     * 4. Returns WebElement if found
     * 5. Fails test if not found or non-recoverable
     * 
     * Usage:
     * try {
     *     driver.findElement(By.id("element")).click();
     * } catch (Exception e) {
     *     WebElement element = exceptionHandler.handleException(e, "Click on Login Button", By.id("element"));
     *     if (element != null) {
     *         element.click(); // Use the healed element
     *     }
     *     // Check exceptionHandler.getLastExceptionResult() for details
     * }
     *
     * @param exception The caught exception
     * @param actionDescription Description of the action that failed
     * @return WebElement if found via healing, null if failed (test will be marked as failed)
     * @deprecated Use handleException(Exception, String, By) for proper locator healing
     */
    @Deprecated
    public WebElement handleException(Exception exception, String actionDescription) {
        return handleException(exception, actionDescription, null);
    }

    /**
     * Main exception handling method with locator information
     * 
     * Enhanced to return WebElement when healing succeeds, null when fails
     * Stores exception details in lastExceptionResult for reference
     *
     * @param exception The caught exception
     * @param actionDescription Description of the action that failed
     * @param locator The locator that was being used (optional, required for healing)
     * @return WebElement if healing successful and element found, null otherwise (test failed)
     */
    public WebElement handleException(Exception exception, String actionDescription, By locator) {

        ExceptionResult result = new ExceptionResult();
        result.setException(exception);
        result.setActionDescription(actionDescription);
        result.setLocator(locator);
        result.setTimestamp(System.currentTimeMillis());

        // Store result for reference
        this.lastExceptionResult = result;

        // Capture screenshot if enabled
        if (takeScreenshotOnFailure) {
            captureScreenshotIfEnabled();
        }

        // Get exception class name for checking
        String exceptionClassName = exception.getClass().getName();
        String exceptionMessage = exception.getMessage() != null ? exception.getMessage().toLowerCase() : "";

        // Handle specific exception types
        // Check by class type first, then by class name (for compatibility)

        if (exception instanceof NoSuchElementException) {
            result = handleNoSuchElementException((NoSuchElementException) exception, actionDescription, locator);
        }
        else if (exception instanceof TimeoutException) {
            result = handleTimeoutException((TimeoutException) exception, actionDescription, locator);
        }
        else if (exception instanceof StaleElementReferenceException) {
            result = handleStaleElementException((StaleElementReferenceException) exception, actionDescription, locator);
        }
        else if (exception instanceof ElementNotInteractableException) {
            result = handleElementNotInteractableException((ElementNotInteractableException) exception, actionDescription, locator);
        }
        // Check by class name for ElementClickInterceptedException (Selenium 4.x only)
        else if (exceptionClassName.contains("ElementClickInterceptedException") ||
                exceptionMessage.contains("click intercepted") ||
                exceptionMessage.contains("element click intercepted")) {
            result = handleElementClickInterceptedException(exception, actionDescription, locator);
        }
        else if (exception instanceof InvalidElementStateException) {
            result = handleInvalidElementStateException((InvalidElementStateException) exception, actionDescription, locator);
        }
        else if (exception instanceof NoSuchSessionException) {
            result = handleNoSuchSessionException((NoSuchSessionException) exception, actionDescription);
        }
        else if (exception instanceof SessionNotCreatedException) {
            result = handleSessionNotCreatedException((SessionNotCreatedException) exception, actionDescription);
        }
        else if (exception instanceof UnreachableBrowserException) {
            result = handleUnreachableBrowserException((UnreachableBrowserException) exception, actionDescription);
        }
        else if (exception instanceof InvalidSelectorException) {
            result = handleInvalidSelectorException((InvalidSelectorException) exception, actionDescription, locator);
        }
        else if (exception instanceof NoAlertPresentException) {
            result = handleNoAlertPresentException((NoAlertPresentException) exception, actionDescription);
        }
        else if (exception instanceof NoSuchWindowException) {
            result = handleNoSuchWindowException((NoSuchWindowException) exception, actionDescription);
        }
        else if (exception instanceof NoSuchFrameException) {
            result = handleNoSuchFrameException((NoSuchFrameException) exception, actionDescription);
        }
        // Check by class name for UnhandledAlertException (compatibility)
        else if (exceptionClassName.contains("UnhandledAlertException") ||
                exceptionMessage.contains("unexpected alert")) {
            result = handleUnhandledAlertException(exception, actionDescription);
        }
        else if (exception instanceof JavascriptException) {
            result = handleJavascriptException((JavascriptException) exception, actionDescription);
        }
        // Check for additional exception types by message pattern
        else if (exceptionMessage.contains("element is not clickable") ||
                exceptionMessage.contains("element not clickable")) {
            result = handleElementNotClickable(exception, actionDescription, locator);
        }
        else if (exceptionMessage.contains("element is not visible") ||
                exceptionMessage.contains("element not displayed")) {
            result = handleElementNotVisible(exception, actionDescription, locator);
        }
        else if (exceptionMessage.contains("unable to find element") ||
                exceptionMessage.contains("no such element")) {
            result = handleNoSuchElementException(new NoSuchElementException(exceptionMessage), actionDescription, locator);
        }
        else if (exception instanceof WebDriverException) {
            result = handleWebDriverException((WebDriverException) exception, actionDescription, locator);
        }
        else {
            result = handleGenericException(exception, actionDescription);
        }

        // Update stored result
        this.lastExceptionResult = result;

        // Log the exception
        logException(result);

        // Handle AI healing for recoverable exceptions with locator
        if (result.isRecoverable() && locator != null && !result.isRetrySuccessful()) {
            WebElement healedElement = attemptAIHealingWithElement(result, locator);
            if (healedElement != null) {
                logger.info(ANSI_GREEN + "✓ AI healing successful - returning healed element" + ANSI_RESET);
                result.setRetrySuccessful(true);
                this.lastExceptionResult = result;
                return healedElement;
            }
        }

        // Mark test as failed if non-recoverable or all retries exhausted
        if (!result.isRecoverable() || (result.isRetryAttempted() && !result.isRetrySuccessful())) {
            String failureMessage = String.format(
                "%s - %s | Action: %s | Locator: %s",
                result.getExceptionType().getDescription(),
                result.getMessage(),
                actionDescription,
                locator != null ? locator.toString() : "N/A"
            );
            
            String stackTrace = getStackTraceAsString(exception);
            
            // CRITICAL: For session-related exceptions, immediately throw to stop test
            if (exception instanceof NoSuchSessionException || 
                exception instanceof SessionNotCreatedException ||
                (exception instanceof WebDriverException && 
                 exception.getMessage() != null && 
                 exception.getMessage().contains("session"))) {
                markTestFailed("CRITICAL: " + failureMessage, exception);
                throw new RuntimeException("Driver session invalid - test cannot continue: " + failureMessage, exception);
            }
            
            markTestFailed("Unknown Failure: " + failureMessage, exception);
            logger.info(ANSI_BLUE + "✗ Test marked as failed: " + failureMessage + ANSI_RESET);
            
            // For other non-recoverable exceptions, also throw to stop test
            throw new AssertionError("Test failed: " + failureMessage, exception);
        }

        return null; // Healing failed or not applicable
    }

    /**
     * Convert exception stack trace to string
     */
    private String getStackTraceAsString(Exception exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * Attempt AI healing for failed locator and return the WebElement
     * 
     * @param result ExceptionResult containing exception details
     * @param originalLocator Original locator that failed
     * @return WebElement if healing successful, null otherwise
     */
    private WebElement attemptAIHealingWithElement(ExceptionResult result, By originalLocator) {
        logger.info(ANSI_CYAN + "🔧 Attempting AI healing for failed locator: " + originalLocator + ANSI_RESET);
        
        long startTime = System.currentTimeMillis();
        AIHealerService.HealingEvent healingEvent = new AIHealerService.HealingEvent();
        healingEvent.actionDescription = result.getActionDescription();
        
        try {
            // Extract locator type and value
            AIHealerService.LocatorUtils.LocatorInfo locatorInfo = 
                    AIHealerService.LocatorUtils.extractLocatorTypeAndValue(originalLocator);
            
            if (locatorInfo == null) {
                logger.info(ANSI_BLUE + "⚠ Could not extract locator information for AI healing" + ANSI_RESET);
                healingEvent.success = false;
                AIHealerService.getReport().recordHealing(healingEvent);
                return null;
            }
            
            healingEvent.originalLocatorType = locatorInfo.type;
            healingEvent.originalLocatorValue = locatorInfo.value;
            
            // Get current page source
            String pageSource = Objects.requireNonNull(driver).getPageSource();
            if (pageSource == null || pageSource.isEmpty()) {
                logger.info(ANSI_BLUE + "⚠ Page source is empty, cannot perform AI healing" + ANSI_RESET);
                healingEvent.success = false;
                AIHealerService.getReport().recordHealing(healingEvent);
                return null;
            }
            
            logger.info(ANSI_CYAN + String.format("🔍 AI Healing - Locator Type: %s, Value: %s", 
                    locatorInfo.type, locatorInfo.value) + ANSI_RESET);
            
            // Generate cache key for usage tracking
            String cacheKey = AIHealerService.getCache().generateCacheKey(
                locatorInfo.type, locatorInfo.value, null);
            
            // Check if this is coming from cache
            AIHealerService.CachedHealedLocator cachedCheck = AIHealerService.getCache().get(cacheKey);
            healingEvent.fromCache = (cachedCheck != null);
            
            // Attempt to find closest match using AI (get HealedLocator with alternatives)
            AIHealerService.HealedLocator healedResult = AIHealerService.findClosestMatchWithAlternatives(
                    locatorInfo.type, 
                    locatorInfo.value, 
                    pageSource
            );
            
            if (healedResult == null) {
                logger.info(ANSI_BLUE + "⚠ AI healing did not find a suitable alternative locator" + ANSI_RESET);
                result.addRecoverySuggestion("AI healing attempted but no alternative locator found");
                healingEvent.success = false;
                healingEvent.healingTimeMs = System.currentTimeMillis() - startTime;
                AIHealerService.getReport().recordHealing(healingEvent);
                return null;
            }
            
            // Record healing details
            healingEvent.healedLocatorType = healedResult.locatorStrategy;
            healingEvent.healedLocatorValue = healedResult.locatorValue;
            healingEvent.confidenceScore = healedResult.confidenceScore;
            healingEvent.alternatives = new ArrayList<>(healedResult.fallbackOptions);
            healingEvent.platform = healedResult.platform.name();
            healingEvent.healingMethod = healingEvent.fromCache ? "cache" : "local";
            
            // Build list of locators to try (primary + alternatives, max 3)
            java.util.List<LocatorAttempt> locatorsToTry = new java.util.ArrayList<>();
            
            // 1. Add primary healed locator
            By primaryLocator = reconstructByLocator(healedResult.locatorStrategy, healedResult.locatorValue);
            locatorsToTry.add(new LocatorAttempt(
                primaryLocator,
                healedResult.locatorStrategy + ": " + healedResult.locatorValue,
                "Primary healed locator"
            ));
            
            // 2. Add alternatives (parse from string format, max 2 more to reach total of 3)
            int alternativesToAdd = Math.min(2, healedResult.fallbackOptions.size());
            for (int i = 0; i < alternativesToAdd; i++) {
                String altString = healedResult.fallbackOptions.get(i);
                By altLocator = parseAlternativeLocator(altString);
                if (altLocator != null) {
                    locatorsToTry.add(new LocatorAttempt(
                        altLocator,
                        altString,
                        "Alternative locator " + (i + 1)
                    ));
                }
            }
            
            logger.info(ANSI_CYAN + String.format("🔄 Attempting to find element with %d locator(s)", locatorsToTry.size()) + ANSI_RESET);
            
            // Try each locator until one works
            for (int attempt = 0; attempt < locatorsToTry.size(); attempt++) {
                LocatorAttempt locatorAttempt = locatorsToTry.get(attempt);
                
                try {
                    logger.info(ANSI_CYAN + String.format("🔄 AI Healing Retry attempt %d/%d - %s", 
                        attempt + 1, locatorsToTry.size(), locatorAttempt.description) + ANSI_RESET);
                    logger.info(ANSI_CYAN + String.format("  🎯 Trying: %s", locatorAttempt.displayString) + ANSI_RESET);
                    
                    Thread.sleep(500); // Brief wait before each attempt
                    
                    WebElement element = driver.findElement(locatorAttempt.locator);
                    if (element != null && element.isDisplayed()) {
                        logger.info(ANSI_GREEN + "✓ AI Healing successful - element found!" + ANSI_RESET);
                        logger.info(ANSI_GREEN + String.format("  ✓ Working locator: %s", locatorAttempt.displayString) + ANSI_RESET);
                        result.addRecoverySuggestion("✓ AI healing successful - use this locator: " + locatorAttempt.displayString);
                        result.setHealedLocator(locatorAttempt.displayString);
                        result.setRetryAttempted(true);
                        result.setRetrySuccessful(true);
                        
                        // 📊 Record successful cache usage
                        AIHealerService.getCache().recordUsage(cacheKey, true);
                        
                        // 📊 Record successful healing event
                        healingEvent.success = true;
                        healingEvent.healingTimeMs = System.currentTimeMillis() - startTime;
                        AIHealerService.getReport().recordHealing(healingEvent);
                        
                        return element; // Return the found element
                    }
                } catch (Exception retryException) {
                    logger.info(ANSI_BLUE + String.format("⚠ AI Healing retry %d failed: %s", 
                            attempt + 1, retryException.getMessage()) + ANSI_RESET);
                    
                    if (attempt == locatorsToTry.size() - 1) {
                        // Last attempt failed - record failure and potentially invalidate cache
                        AIHealerService.getCache().recordUsage(cacheKey, false);
                        
                        result.addRecoverySuggestion("AI healing found " + locatorsToTry.size() + 
                            " alternative locator(s) but none were accessible");
                        result.addRecoverySuggestion("Tried locators:");
                        for (LocatorAttempt la : locatorsToTry) {
                            result.addRecoverySuggestion("  - " + la.displayString);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            logger.info(ANSI_BLUE + "✗ AI healing process failed with exception: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace();
            result.addRecoverySuggestion("AI healing process encountered an error: " + e.getMessage());
            healingEvent.success = false;
            healingEvent.healingTimeMs = System.currentTimeMillis() - startTime;
            AIHealerService.getReport().recordHealing(healingEvent);
        }
        
        result.setRetryAttempted(true);
        result.setRetrySuccessful(false);
        
        // 📊 Record failed healing event
        healingEvent.success = false;
        healingEvent.healingTimeMs = System.currentTimeMillis() - startTime;
        AIHealerService.getReport().recordHealing(healingEvent);
        
        return null; // All attempts failed
    }

    /**
     * Attempt AI healing for failed locator (legacy method for backward compatibility)
     * 
     * @param result ExceptionResult containing exception details
     * @param originalLocator Original locator that failed
     * @return true if healing successful and element found, false otherwise
     * @deprecated Use attemptAIHealingWithElement() instead
     */
    @Deprecated
    private boolean attemptAIHealing(ExceptionResult result, By originalLocator) {
        return attemptAIHealingWithElement(result, originalLocator) != null;
    }
    
    /**
     * Original attemptAIHealing implementation (kept for reference, not used)
     */
    private boolean attemptAIHealingLegacy(ExceptionResult result, By originalLocator) {
        logger.info("Attempting AI healing for failed locator: " + originalLocator);
        
        try {
            // Extract locator type and value
            AIHealerService.LocatorUtils.LocatorInfo locatorInfo = 
                    AIHealerService.LocatorUtils.extractLocatorTypeAndValue(originalLocator);
            
            if (locatorInfo == null) {
                logger.warning("Could not extract locator information for AI healing");
                return false;
            }
            
            // Get current page source
            String pageSource = Objects.requireNonNull(driver).getPageSource();
            if (pageSource == null || pageSource.isEmpty()) {
                logger.warning("Page source is empty, cannot perform AI healing");
                return false;
            }
            
            logger.info(String.format("AI Healing - Locator Type: %s, Value: %s", 
                    locatorInfo.type, locatorInfo.value));
            
            // Attempt to find closest match using AI (get HealedLocator with alternatives)
            AIHealerService.HealedLocator healedResult = AIHealerService.findClosestMatchWithAlternatives(
                    locatorInfo.type, 
                    locatorInfo.value, 
                    pageSource
            );
            
            if (healedResult == null) {
                logger.warning("AI healing did not find a suitable alternative locator");
                result.addRecoverySuggestion("AI healing attempted but no alternative locator found");
                return false;
            }
            
            // Build list of locators to try (primary + alternatives, max 3)
            java.util.List<LocatorAttempt> locatorsToTry = new java.util.ArrayList<>();
            
            // 1. Add primary healed locator
            By primaryLocator = reconstructByLocator(healedResult.locatorStrategy, healedResult.locatorValue);
            locatorsToTry.add(new LocatorAttempt(
                primaryLocator,
                healedResult.locatorStrategy + ": " + healedResult.locatorValue,
                "Primary healed locator"
            ));
            
            // 2. Add alternatives (parse from string format, max 2 more to reach total of 3)
            int alternativesToAdd = Math.min(2, healedResult.fallbackOptions.size());
            for (int i = 0; i < alternativesToAdd; i++) {
                String altString = healedResult.fallbackOptions.get(i);
                By altLocator = parseAlternativeLocator(altString);
                if (altLocator != null) {
                    locatorsToTry.add(new LocatorAttempt(
                        altLocator,
                        altString,
                        "Alternative locator " + (i + 1)
                    ));
                }
            }
            
            logger.info(String.format("Attempting to find element with %d locator(s)", locatorsToTry.size()));
            
            // Try each locator until one works
            for (int attempt = 0; attempt < locatorsToTry.size(); attempt++) {
                LocatorAttempt locatorAttempt = locatorsToTry.get(attempt);
                
                try {
                    logger.info(String.format("AI Healing Retry attempt %d/%d - %s", 
                        attempt + 1, locatorsToTry.size(), locatorAttempt.description));
                    logger.info(String.format("  Trying: %s", locatorAttempt.displayString));
                    
                    Thread.sleep(500); // Brief wait before each attempt
                    
                    WebElement element = driver.findElement(locatorAttempt.locator);
                    if (element != null && element.isDisplayed()) {
                        logger.info("✓ AI Healing successful - element found!");
                        logger.info(String.format("  Working locator: %s", locatorAttempt.displayString));
                        result.addRecoverySuggestion("✓ AI healing successful - use this locator: " + locatorAttempt.displayString);
                        result.setHealedLocator(locatorAttempt.displayString);
                        result.setRetryAttempted(true);
                        return true;
                    }
                } catch (Exception retryException) {
                    logger.warning(String.format("AI Healing retry %d failed: %s", 
                            attempt + 1, retryException.getMessage()));
                    
                    if (attempt == locatorsToTry.size() - 1) {
                        // Last attempt failed
                        result.addRecoverySuggestion("AI healing found " + locatorsToTry.size() + 
                            " alternative locator(s) but none were accessible");
                        result.addRecoverySuggestion("Tried locators:");
                        for (LocatorAttempt la : locatorsToTry) {
                            result.addRecoverySuggestion("  - " + la.displayString);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            logger.severe("AI healing process failed with exception: " + e.getMessage());
            e.printStackTrace();
            result.addRecoverySuggestion("AI healing process encountered an error: " + e.getMessage());
        }
        
        return false;
    }

    /**
     * Helper class to hold locator attempt information
     */
    private static class LocatorAttempt {
        By locator;
        String displayString;
        String description;

        LocatorAttempt(By locator, String displayString, String description) {
            this.locator = locator;
            this.displayString = displayString;
            this.description = description;
        }
    }

    /**
     * Reconstruct a By object from locator strategy and value
     */
    private By reconstructByLocator(String strategy, String value) {
        try {
            switch (strategy.toLowerCase()) {
                case "id":
                    return AppiumBy.id(value);
                case "accessibility":
                case "aid":
                    return AppiumBy.accessibilityId(value);
                case "name":
                    return AppiumBy.name(value);
                case "classname":
                case "cn":
                    return By.className(value);
                case "xpath":
                    return By.xpath(value);
                case "uiautomator":
                    return AppiumBy.androidUIAutomator(value);
                case "iosclasschain":
                    return AppiumBy.iOSClassChain(value);
                case "iospredicate":
                    return AppiumBy.iOSNsPredicateString(value);
                default:
                    logger.info(ANSI_BLUE + "⚠ Unknown locator strategy: " + strategy + ", defaulting to xpath" + ANSI_RESET);
                    return By.xpath(value);
            }
        } catch (Exception e) {
            logger.info(ANSI_BLUE + "⚠ Failed to reconstruct locator: " + e.getMessage() + ANSI_RESET);
            return By.xpath(value); // Fallback
        }
    }

    /**
     * Parse alternative locator string format to By object
     * Format examples:
     *   AppiumBy.accessibilityId("value")
     *   AppiumBy.iOSClassChain("**element")
     *   AppiumBy.xpath("//element")
     */
    private By parseAlternativeLocator(String altString) {
        try {
            // Extract method and value using regex
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "AppiumBy\\.(\\w+)\\(\"(.+?)\"\\)"
            );
            java.util.regex.Matcher matcher = pattern.matcher(altString);
            
            if (matcher.find()) {
                String method = matcher.group(1);
                String value = matcher.group(2);
                
                switch (method.toLowerCase()) {
                    case "accessibilityid":
                        return AppiumBy.accessibilityId(value);
                    case "id":
                        return AppiumBy.id(value);
                    case "name":
                        return AppiumBy.name(value);
                    case "xpath":
                        return By.xpath(value);
                    case "iosclasschain":
                        return AppiumBy.iOSClassChain(value);
                    case "iosnspredicatestring":
                        return AppiumBy.iOSNsPredicateString(value);
                    case "androiduiautomator":
                        return AppiumBy.androidUIAutomator(value);
                    default:
                        logger.info(ANSI_BLUE + "⚠ Unknown alternative locator method: " + method + ANSI_RESET);
                        return null;
                }
            }
            
            logger.info(ANSI_BLUE + "⚠ Could not parse alternative locator: " + altString + ANSI_RESET);
            return null;
            
        } catch (Exception e) {
            logger.info(ANSI_BLUE + "⚠ Failed to parse alternative locator: " + altString + " - " + e.getMessage() + ANSI_RESET);
            return null;
        }
    }

    /**
     * Handle NoSuchElementException
     */
    private ExceptionResult handleNoSuchElementException(NoSuchElementException e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.NO_SUCH_ELEMENT);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(HIGH);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Element not found: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Verify the locator strategy is correct");
        result.addRecoverySuggestion("2. Check if element is present in the page source");
        result.addRecoverySuggestion("3. Increase implicit/explicit wait time");
        result.addRecoverySuggestion("4. Check if element is inside a frame/iframe");
        result.addRecoverySuggestion("5. Verify app is on the correct screen");
        result.addRecoverySuggestion("6. Check if element is dynamically loaded");

        return result;
    }

    /**
     * Handle TimeoutException
     */
    private ExceptionResult handleTimeoutException(TimeoutException e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.TIMEOUT);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Timeout occurred: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Increase wait timeout duration");
        result.addRecoverySuggestion("2. Check if app/element is loading slowly");
        result.addRecoverySuggestion("3. Verify network connectivity");
        result.addRecoverySuggestion("4. Check if element appears after specific condition");
        result.addRecoverySuggestion("5. Use explicit waits with proper conditions");

        return result;
    }

    /**
     * Handle StaleElementReferenceException
     */
    private ExceptionResult handleStaleElementException(StaleElementReferenceException e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.STALE_ELEMENT);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Element is stale (DOM changed): %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Re-find the element before interacting");
        result.addRecoverySuggestion("2. Avoid storing WebElement references for long periods");
        result.addRecoverySuggestion("3. Check if page/screen refreshed or navigated");
        result.addRecoverySuggestion("4. Use Page Object Model with dynamic element location");
        result.addRecoverySuggestion("5. Implement retry logic to re-find element");

        // Auto-retry if configured
        if (retryCount < maxRetries && locator != null) {
            result.setRetryAttempted(true);
            result.setRetrySuccessful(retryStaleElement(locator));
        }

        return result;
    }

    /**
     * Handle ElementNotInteractableException
     */
    private ExceptionResult handleElementNotInteractableException(ElementNotInteractableException e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.ELEMENT_NOT_INTERACTABLE);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Element not interactable: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Check if element is visible on screen");
        result.addRecoverySuggestion("2. Verify element is not hidden or covered by another element");
        result.addRecoverySuggestion("3. Scroll element into view before interaction");
        result.addRecoverySuggestion("4. Wait for element to become enabled");
        result.addRecoverySuggestion("5. Check if element is inside a scrollable container");
        result.addRecoverySuggestion("6. Try using JavaScript/Actions to interact");

        return result;
    }

    /**
     * Handle ElementClickInterceptedException (or similar click issues)
     * Uses Exception instead of specific class for compatibility
     */
    private ExceptionResult handleElementClickInterceptedException(Exception e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.CLICK_INTERCEPTED);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Element click intercepted: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Another element is covering the target element");
        result.addRecoverySuggestion("2. Close any overlays/modals/popups first");
        result.addRecoverySuggestion("3. Wait for animations to complete");
        result.addRecoverySuggestion("4. Scroll element to center of screen");
        result.addRecoverySuggestion("5. Use Actions class or JavaScript click");
        result.addRecoverySuggestion("6. Check z-index of elements");

        return result;
    }

    /**
     * Handle element not clickable (common mobile issue)
     */
    private ExceptionResult handleElementNotClickable(Exception e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.ELEMENT_NOT_CLICKABLE);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Element is not clickable: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Element may be covered by another element");
        result.addRecoverySuggestion("2. Element might be outside viewport");
        result.addRecoverySuggestion("3. Scroll element into center of screen");
        result.addRecoverySuggestion("4. Wait for page/app animations to complete");
        result.addRecoverySuggestion("5. Try tap using TouchAction instead of click");

        return result;
    }

    /**
     * Handle element not visible
     */
    private ExceptionResult handleElementNotVisible(Exception e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.ELEMENT_NOT_VISIBLE);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Element is not visible: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Wait for element to become visible");
        result.addRecoverySuggestion("2. Check if element is hidden by CSS");
        result.addRecoverySuggestion("3. Scroll to make element visible");
        result.addRecoverySuggestion("4. Verify element display property");

        return result;
    }

    /**
     * Handle InvalidElementStateException
     */
    private ExceptionResult handleInvalidElementStateException(InvalidElementStateException e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.INVALID_ELEMENT_STATE);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Element in invalid state: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Check if element is disabled");
        result.addRecoverySuggestion("2. Verify element supports the attempted action");
        result.addRecoverySuggestion("3. Wait for element to reach expected state");
        result.addRecoverySuggestion("4. Check element's enabled/disabled attribute");

        return result;
    }

    /**
     * Handle NoSuchSessionException
     */
    private ExceptionResult handleNoSuchSessionException(NoSuchSessionException e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.NO_SUCH_SESSION);
        result.setActionDescription(action);
        result.setSeverity(CRITICAL);
        result.setRecoverable(false);

        result.setMessage(String.format(
                "Session not found or expired: %s\nAction: %s",
                e.getMessage(), action
        ));

        result.addRecoverySuggestion("1. Driver session has been closed or expired");
        result.addRecoverySuggestion("2. Restart the driver/session");
        result.addRecoverySuggestion("3. Check if app crashed");
        result.addRecoverySuggestion("4. Verify Appium server is running");
        result.addRecoverySuggestion("5. Check session timeout settings");

        return result;
    }

    /**
     * Handle SessionNotCreatedException
     */
    private ExceptionResult handleSessionNotCreatedException(SessionNotCreatedException e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.SESSION_NOT_CREATED);
        result.setActionDescription(action);
        result.setSeverity(CRITICAL);
        result.setRecoverable(false);

        result.setMessage(String.format(
                "Failed to create session: %s\nAction: %s",
                e.getMessage(), action
        ));

        result.addRecoverySuggestion("1. Check if Appium server is running");
        result.addRecoverySuggestion("2. Verify desired capabilities are correct");
        result.addRecoverySuggestion("3. Check if device/emulator is connected");
        result.addRecoverySuggestion("4. Verify app path and package/bundle ID");
        result.addRecoverySuggestion("5. Check Appium server logs for details");
        result.addRecoverySuggestion("6. Ensure no other sessions using same device");

        return result;
    }

    /**
     * Handle WebDriverException (generic)
     */
    private ExceptionResult handleWebDriverException(WebDriverException e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.WEBDRIVER_EXCEPTION);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(HIGH);
        result.setRecoverable(false);

        result.setMessage(String.format(
                "WebDriver exception: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Check exception message for specific details");
        result.addRecoverySuggestion("2. Verify driver is properly initialized");
        result.addRecoverySuggestion("3. Check Appium server logs");
        result.addRecoverySuggestion("4. Restart driver session if needed");

        return result;
    }

    /**
     * Handle UnreachableBrowserException
     */
    private ExceptionResult handleUnreachableBrowserException(UnreachableBrowserException e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.UNREACHABLE_BROWSER);
        result.setActionDescription(action);
        result.setSeverity(CRITICAL);
        result.setRecoverable(false);

        result.setMessage(String.format(
                "Cannot reach browser/app: %s\nAction: %s",
                e.getMessage(), action
        ));

        result.addRecoverySuggestion("1. App or Appium server crashed");
        result.addRecoverySuggestion("2. Network connection lost");
        result.addRecoverySuggestion("3. Restart Appium server");
        result.addRecoverySuggestion("4. Verify device connectivity");
        result.addRecoverySuggestion("5. Check if app is still running");

        return result;
    }

    /**
     * Handle InvalidSelectorException
     */
    private ExceptionResult handleInvalidSelectorException(InvalidSelectorException e, String action, By locator) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.INVALID_SELECTOR);
        result.setActionDescription(action);
        result.setLocator(locator);
        result.setSeverity(HIGH);
        result.setRecoverable(false);

        result.setMessage(String.format(
                "Invalid selector syntax: %s\nAction: %s\nLocator: %s",
                e.getMessage(), action, locator != null ? locator.toString() : "N/A"
        ));

        result.addRecoverySuggestion("1. Check XPath/CSS selector syntax");
        result.addRecoverySuggestion("2. Verify selector is valid for platform (iOS/Android)");
        result.addRecoverySuggestion("3. Test selector in Appium Inspector");
        result.addRecoverySuggestion("4. Use proper escaping for special characters");

        return result;
    }

    /**
     * Handle NoAlertPresentException
     */
    private ExceptionResult handleNoAlertPresentException(NoAlertPresentException e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.NO_ALERT_PRESENT);
        result.setActionDescription(action);
        result.setSeverity(LOW);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "No alert/popup present: %s\nAction: %s",
                e.getMessage(), action
        ));

        result.addRecoverySuggestion("1. Verify alert/popup is actually displayed");
        result.addRecoverySuggestion("2. Wait for alert to appear before interacting");
        result.addRecoverySuggestion("3. Check if alert was already dismissed");

        return result;
    }

    /**
     * Handle NoSuchWindowException
     */
    private ExceptionResult handleNoSuchWindowException(NoSuchWindowException e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.NO_SUCH_WINDOW);
        result.setActionDescription(action);
        result.setSeverity(HIGH);
        result.setRecoverable(false);

        result.setMessage(String.format(
                "Window/context not found: %s\nAction: %s",
                e.getMessage(), action
        ));

        result.addRecoverySuggestion("1. Window/context has been closed");
        result.addRecoverySuggestion("2. Switch to available window/context");
        result.addRecoverySuggestion("3. Verify window handle is correct");
        result.addRecoverySuggestion("4. Check if app switched contexts");

        return result;
    }

    /**
     * Handle NoSuchFrameException
     */
    private ExceptionResult handleNoSuchFrameException(NoSuchFrameException e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.NO_SUCH_FRAME);
        result.setActionDescription(action);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        result.setMessage(String.format(
                "Frame not found: %s\nAction: %s",
                e.getMessage(), action
        ));

        result.addRecoverySuggestion("1. Verify frame name/ID is correct");
        result.addRecoverySuggestion("2. Wait for frame to load");
        result.addRecoverySuggestion("3. Switch to default content first");
        result.addRecoverySuggestion("4. Check if frame exists in page source");

        return result;
    }

    /**
     * Handle UnhandledAlertException
     * Uses Exception for compatibility
     */
    private ExceptionResult handleUnhandledAlertException(Exception e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.UNHANDLED_ALERT);
        result.setActionDescription(action);
        result.setSeverity(MEDIUM);
        result.setRecoverable(true);

        String alertText = extractAlertText(e.getMessage());

        result.setMessage(String.format(
                "Unhandled alert present: %s\nAction: %s\nAlert text: %s",
                e.getMessage(), action, alertText
        ));

        result.addRecoverySuggestion("1. Accept or dismiss the alert");
        result.addRecoverySuggestion("2. Add alert handling before action");
        result.addRecoverySuggestion("3. Use autoAcceptAlerts capability");

        // Auto-handle alert if configured
        result.setRetryAttempted(true);
        result.setRetrySuccessful(handleAlert());

        return result;
    }

    /**
     * Handle JavascriptException
     */
    private ExceptionResult handleJavascriptException(JavascriptException e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.JAVASCRIPT_EXCEPTION);
        result.setActionDescription(action);
        result.setSeverity(MEDIUM);
        result.setRecoverable(false);

        result.setMessage(String.format(
                "JavaScript execution failed: %s\nAction: %s",
                e.getMessage(), action
        ));

        result.addRecoverySuggestion("1. Check JavaScript syntax");
        result.addRecoverySuggestion("2. Verify JavaScript is supported in current context");
        result.addRecoverySuggestion("3. Check if element exists for JavaScript execution");

        return result;
    }

    /**
     * Handle generic exceptions
     */
    private ExceptionResult handleGenericException(Exception e, String action) {
        ExceptionResult result = new ExceptionResult();
        result.setException(e);
        result.setExceptionType(ExceptionType.GENERIC);
        result.setActionDescription(action);
        result.setSeverity(MEDIUM);
        result.setRecoverable(false);

        result.setMessage(String.format(
                "Unexpected exception: %s\nAction: %s\nException class: %s",
                e.getMessage(), action, e.getClass().getSimpleName()
        ));

        result.addRecoverySuggestion("1. Check exception details and stack trace");
        result.addRecoverySuggestion("2. Verify action is valid for current app state");
        result.addRecoverySuggestion("3. Review recent code changes");

        return result;
    }

    /**
     * Extract alert text from exception message
     */
    private String extractAlertText(String message) {
        if (message == null) return "N/A";

        // Try to extract alert text from exception message
        int startIndex = message.indexOf("{Alert text : ");
        if (startIndex != -1) {
            int endIndex = message.indexOf("}", startIndex);
            if (endIndex != -1) {
                return message.substring(startIndex + 14, endIndex);
            }
        }

        return "Unable to extract alert text";
    }

    /**
     * Retry logic for stale element
     */
    private boolean retryStaleElement(By locator) {
        try {
            retryCount++;
            logger.info(ANSI_CYAN + String.format("🔄 Retry attempt %d/%d for stale element: %s", retryCount, maxRetries, locator) + ANSI_RESET);

            Thread.sleep(1000); // Wait before retry
            WebElement element = Objects.requireNonNull(driver).findElement(locator);
            return element != null;

        } catch (Exception e) {
            logger.info(ANSI_BLUE + "⚠ Retry failed: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    /**
     * Handle unexpected alerts
     */
    private boolean handleAlert() {
        try {
            Alert alert = Objects.requireNonNull(driver).switchTo().alert();
            logger.info(ANSI_CYAN + "📢 Auto-accepting alert: " + alert.getText() + ANSI_RESET);
            alert.accept();
            return true;
        } catch (Exception e) {
            logger.info(ANSI_BLUE + "⚠ Failed to handle alert: " + e.getMessage() + ANSI_RESET);
            return false;
        }
    }

    /**
     * Log exception details
     */
    private void logException(ExceptionResult result) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n==================== EXCEPTION HANDLED ====================\n");
        logMessage.append("Type: ").append(result.getExceptionType()).append("\n");
        logMessage.append("Severity: ").append(result.getSeverity()).append("\n");
        logMessage.append("Action: ").append(result.getActionDescription()).append("\n");
        logMessage.append("Recoverable: ").append(result.isRecoverable()).append("\n");
        logMessage.append("Message: ").append(result.getMessage()).append("\n");

        if (result.getLocator() != null) {
            logMessage.append("Locator: ").append(result.getLocator()).append("\n");
        }

        if (result.getScreenshotPath() != null) {
            logMessage.append("Screenshot: ").append(result.getScreenshotPath()).append("\n");
        }

        if (result.isRetryAttempted()) {
            logMessage.append("Retry Attempted: Yes\n");
            logMessage.append("Retry Successful: ").append(result.isRetrySuccessful()).append("\n");
        }

        logMessage.append("\nRecovery Suggestions:\n");
        for (int i = 0; i < result.getRecoverySuggestions().size(); i++) {
            logMessage.append("  ").append(i + 1).append(". ")
                    .append(result.getRecoverySuggestions().get(i)).append("\n");
        }

        logMessage.append("===========================================================\n");

        // Log everything in blue to avoid red console output
        if (result.getSeverity() == CRITICAL) {
            logger.info(ANSI_BLUE + logMessage.toString() + ANSI_RESET);
        } else if (result.getSeverity() == HIGH) {
            logger.info(ANSI_BLUE + logMessage.toString() + ANSI_RESET);
        } else {
            logger.info(ANSI_CYAN + logMessage.toString() + ANSI_RESET);
        }
    }

    /**
     * Reset retry counter (call this between independent test actions)
     */
    public void resetRetryCounter() {
        this.retryCount = 0;
    }

    /**
     * Exception Result class - contains all information about handled exception
     */
    public static class ExceptionResult {
    
        private Exception exception;
        private ExceptionType exceptionType;
        private Severity severity;
        private String actionDescription;
        private By locator;
        private String message;
        private boolean recoverable;
        private List<String> recoverySuggestions;
        private String screenshotPath;
        private long timestamp;
        private boolean retryAttempted;
        private boolean retrySuccessful;
        private String healedLocator;
    
        public ExceptionResult() {
            this.recoverySuggestions = new ArrayList<>();
            this.recoverable = false;
            this.retryAttempted = false;
            this.retrySuccessful = false;
            this.healedLocator = null;
        }
    
        // Getters and Setters
        public Exception getException() {
            return exception;
        }
    
        public void setException(Exception exception) {
            this.exception = exception;
        }
    
        public ExceptionType getExceptionType() {
            return exceptionType;
        }
    
        public void setExceptionType(ExceptionType exceptionType) {
            this.exceptionType = exceptionType;
        }
    
        public Severity getSeverity() {
            return severity;
        }
    
        public void setSeverity(Severity severity) {
            this.severity = severity;
        }
    
        public String getActionDescription() {
            return actionDescription;
        }
    
        public void setActionDescription(String actionDescription) {
            this.actionDescription = actionDescription;
        }
    
        public By getLocator() {
            return locator;
        }
    
        public void setLocator(By locator) {
            this.locator = locator;
        }
    
        public String getMessage() {
            return message;
        }
    
        public void setMessage(String message) {
            this.message = message;
        }
    
        public boolean isRecoverable() {
            return recoverable;
        }
    
        public void setRecoverable(boolean recoverable) {
            this.recoverable = recoverable;
        }
    
        public List<String> getRecoverySuggestions() {
            return recoverySuggestions;
        }
    
        public void setRecoverySuggestions(List<String> recoverySuggestions) {
            this.recoverySuggestions = recoverySuggestions;
        }
    
        public void addRecoverySuggestion(String suggestion) {
            this.recoverySuggestions.add(suggestion);
        }
    
        public String getScreenshotPath() {
            return screenshotPath;
        }
    
        public void setScreenshotPath(String screenshotPath) {
            this.screenshotPath = screenshotPath;
        }
    
        public long getTimestamp() {
            return timestamp;
        }
    
        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    
        public boolean isRetryAttempted() {
            return retryAttempted;
        }
    
        public void setRetryAttempted(boolean retryAttempted) {
            this.retryAttempted = retryAttempted;
        }
    
        public boolean isRetrySuccessful() {
            return retrySuccessful;
        }
    
        public void setRetrySuccessful(boolean retrySuccessful) {
            this.retrySuccessful = retrySuccessful;
        }
    
        public String getHealedLocator() {
            return healedLocator;
        }
    
        public void setHealedLocator(String healedLocator) {
            this.healedLocator = healedLocator;
        }
    
        /**
         * Get formatted exception report
         */
        public String getFormattedReport() {
            StringBuilder report = new StringBuilder();
            report.append("\n╔════════════════════════════════════════════════════════════╗\n");
            report.append("║              EXCEPTION HANDLING REPORT                     ║\n");
            report.append("╠════════════════════════════════════════════════════════════╣\n");
            report.append(String.format("║ Type:        %-45s ║\n", exceptionType));
            report.append(String.format("║ Severity:    %-45s ║\n", severity));
            report.append(String.format("║ Recoverable: %-45s ║\n", recoverable));
            report.append(String.format("║ Action:      %-45s ║\n", truncate(actionDescription, 45)));
    
            if (locator != null) {
                report.append(String.format("║ Locator:     %-45s ║\n", truncate(locator.toString(), 45)));
            }
    
            if (screenshotPath != null) {
                report.append(String.format("║ Screenshot:  %-45s ║\n", truncate(screenshotPath, 45)));
            }
    
            if (retryAttempted) {
                report.append(String.format("║ Retry:       %-45s ║\n",
                        retrySuccessful ? "Attempted - SUCCESS" : "Attempted - FAILED"));
            }
    
            if (healedLocator != null && !healedLocator.isEmpty()) {
                report.append(String.format("║ AI Healed:   %-45s ║\n", truncate(healedLocator, 45)));
            }
    
            report.append("╠════════════════════════════════════════════════════════════╣\n");
            report.append("║ Recovery Suggestions:                                      ║\n");
    
            for (String suggestion : recoverySuggestions) {
                String[] lines = wrapText(suggestion, 55);
                for (String line : lines) {
                    report.append(String.format("║  • %-56s ║\n", line));
                }
            }
    
            report.append("╚════════════════════════════════════════════════════════════╝\n");
    
            return report.toString();
        }
    
        private String truncate(String text, int maxLength) {
            if (text == null) return "";
            return text.length() > maxLength ? text.substring(0, maxLength - 3) + "..." : text;
        }
    
        private String[] wrapText(String text, int maxLength) {
            List<String> lines = new ArrayList<>();
            String[] words = text.split(" ");
            StringBuilder currentLine = new StringBuilder();
    
            for (String word : words) {
                if (currentLine.length() + word.length() + 1 <= maxLength) {
                    if (currentLine.length() > 0) currentLine.append(" ");
                    currentLine.append(word);
                } else {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder(word);
                }
            }
    
            if (currentLine.length() > 0) {
                lines.add(currentLine.toString());
            }
    
            return lines.toArray(new String[0]);
        }
    }

    /**
     * Exception Type Enum - categorizes all possible exceptions
     */
    enum ExceptionType {
        NO_SUCH_ELEMENT("Element Not Found"),
        TIMEOUT("Timeout Exceeded"),
        STALE_ELEMENT("Stale Element Reference"),
        ELEMENT_NOT_INTERACTABLE("Element Not Interactable"),
        ELEMENT_NOT_CLICKABLE("Element Not Clickable"),
        ELEMENT_NOT_VISIBLE("Element Not Visible"),
        CLICK_INTERCEPTED("Click Intercepted"),
        INVALID_ELEMENT_STATE("Invalid Element State"),
        NO_SUCH_SESSION("Session Not Found"),
        SESSION_NOT_CREATED("Session Creation Failed"),
        WEBDRIVER_EXCEPTION("WebDriver Exception"),
        UNREACHABLE_BROWSER("Browser/App Unreachable"),
        INVALID_SELECTOR("Invalid Selector Syntax"),
        NO_ALERT_PRESENT("No Alert Present"),
        NO_SUCH_WINDOW("Window/Context Not Found"),
        NO_SUCH_FRAME("Frame Not Found"),
        UNHANDLED_ALERT("Unhandled Alert Present"),
        JAVASCRIPT_EXCEPTION("JavaScript Execution Failed"),
        GENERIC("Generic Exception"),
        API_FAILURE("Generic Exception");
    
        private final String description;
    
        ExceptionType(String description) {
            this.description = description;
        }
    
        public String getDescription() {
            return description;
        }
    
        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * Severity Enum - indicates how critical the exception is
     */
    enum Severity {
        LOW("Low", 1),
        MEDIUM("Medium", 2),
        HIGH("High", 3),
        CRITICAL("Critical", 4);
    
        private final String description;
        private final int level;
    
        Severity(String description, int level) {
            this.description = description;
            this.level = level;
        }
    
        public String getDescription() {
            return description;
        }
    
        public int getLevel() {
            return level;
        }
    
        @Override
        public String toString() {
            return description;
        }
    }
}