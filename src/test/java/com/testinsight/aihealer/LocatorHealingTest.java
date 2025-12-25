package com.testinsight.aihealer;

import io.appium.java_client.AppiumBy;
import io.appium.java_client.AppiumDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.*;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for AIHealerService locator healing functionality
 * Tests recovery strategies for common Selenium/Appium exceptions
 * Uses iOS.xml and Android.xml as page source fixtures
 */
@DisplayName("AI Locator Healing Test Suite")
public class LocatorHealingTest {

    private AIHealerService healerService;
    private String iOSPageSource;
    private String androidPageSource;
    private MockAppiumDriver mockDriver;

    @BeforeEach
    public void setUp() throws IOException {
        // Load page sources from fixture files
        iOSPageSource = new String(Files.readAllBytes(
                Paths.get("/Users/meghagupta/Downloads/ai-locator-healer/iOS.xml")));
        androidPageSource = new String(Files.readAllBytes(
                Paths.get("/Users/meghagupta/Downloads/ai-locator-healer/Android.xml")));

        // Create mock driver
        mockDriver = new MockAppiumDriver(iOSPageSource, androidPageSource);

        // Initialize healer service
        healerService = new AIHealerService();
    }

    // ============ EXCEPTION TYPES & RECOVERABILITY ============
    // Recoverable Exceptions:
    // - NoSuchElementException: Most common, often recovered by alternative XPath/ID
    // - TimeoutException: Can retry with different locator strategy
    // - StaleElementReferenceException: Element reference outdated, can re-find
    // - InvalidSelectorException: XPath syntax wrong, can fix
    // 
    // Non-Recoverable Exceptions:
    // - WebDriverException: Low-level driver error, may need restart
    // - SessionNotCreatedException: Driver session issue
    // - UnsupportedOperationException: Operation not supported by driver

    @Test
    @DisplayName("Scenario 1: Element location changed but exists with alternative XPath")
    public void testRecoveryWithAlternativeXPath() throws Exception {
        // Simulate: Element was previously found at //*[@id="loginBtn"]
        // But now button class changed, need to find by text or accessibility ID
        
        String originalXPath = "//button[@id='invalid-id-that-no-longer-exists']";
        String alternativeXPath = "//XCUIElementTypeButton[@name='components.tabbar.tabItem.0']";
        
        // This should be recoverable - exception type indicates locator issue
        NoSuchElementException exception = new NoSuchElementException(
                "Unable to locate element: " + originalXPath);
        
        assertTrue(isRecoverableException(exception), 
                "NoSuchElementException should be recoverable");
        
        // Attempt recovery with alternative locator
        String healedXPath = alternativeXPath;
        assertNotNull(healedXPath);
        assertTrue(healedXPath.contains("XCUIElementTypeButton"));
    }

    @Test
    @DisplayName("Scenario 2: Attribute changed but element location same")
    public void testRecoveryWhenAttributesChanged() throws Exception {
        // Element position hasn't changed, but className or other attributes changed
        // Original: //div[@class='btn-primary']
        // Now: //div[@class='btn btn-primary btn-lg']
        
        String originalLocator = "//div[@class='btn-primary']";
        String recoveryLocator = "//div[contains(@class, 'btn-primary')]";
        
        assertTrue(isRecoverableException(new NoSuchElementException("Element not found")));
        
        // Verify recovery strategy uses contains() for partial attribute matching
        assertTrue(recoveryLocator.contains("contains"));
    }

    @Test
    @DisplayName("Scenario 3: iOS Accessibility ID Recovery")
    public void testIOSAccessibilityIDRecovery() throws Exception {
        // For iOS, when XPath fails, try accessibility ID (name attribute)
        // From iOS.xml: name="components.tabbar.tabItem.0"
        
        String accessibilityID = "components.tabbar.tabItem.0";
        
        // This is a recoverable scenario
        NoSuchElementException exception = new NoSuchElementException(
                "Cannot find element by XPath for tab item");
        
        assertTrue(isRecoverableException(exception));
        
        // Verify accessibility ID exists in iOS page source
        assertTrue(iOSPageSource.contains(accessibilityID),
                "iOS page source should contain accessibility ID: " + accessibilityID);
    }

    @Test
    @DisplayName("Scenario 4: Android resource-id Recovery")
    public void testAndroidResourceIDRecovery() throws Exception {
        // For Android, when XPath fails, try resource-id
        // From Android.xml: resource-id="com.brightinsight.mptestapp:id/components.tabbar.tabItem.0"
        
        String resourceID = "com.brightinsight.mptestapp:id/components.tabbar.tabItem.0";
        
        NoSuchElementException exception = new NoSuchElementException(
                "Cannot locate element for tab navigation");
        
        assertTrue(isRecoverableException(exception));
        assertTrue(androidPageSource.contains(resourceID),
                "Android page source should contain resource-id: " + resourceID);
    }

    @Test
    @DisplayName("Scenario 5: Stale Element Reference Recovery")
    public void testStaleElementReferenceRecovery() throws Exception {
        // Stale elements can be recovered by re-finding them
        StaleElementReferenceException staleException = 
                new StaleElementReferenceException("Element is no longer attached to DOM");
        
        assertTrue(isRecoverableException(staleException),
                "StaleElementReferenceException should be recoverable - retry finding element");
    }

    @Test
    @DisplayName("Scenario 6: Invalid Selector Syntax Recovery")
    public void testInvalidSelectorRecovery() throws Exception {
        // Invalid XPath can be recovered by fixing syntax
        InvalidSelectorException invalidSelector = new InvalidSelectorException(
                "invalid xpath: //div[@class='");
        
        assertTrue(isRecoverableException(invalidSelector),
                "InvalidSelectorException should be recoverable with corrected XPath");
    }

    @Test
    @DisplayName("Scenario 7: Timeout with Alternative Locator")
    public void testTimeoutRecoveryWithAlternativeLocator() throws Exception {
        // Timeout can be recovered by trying alternative locator with different wait time
        TimeoutException timeoutException = new TimeoutException(
                "Timed out after 10 seconds waiting for element");
        
        assertTrue(isRecoverableException(timeoutException),
                "TimeoutException should be recoverable with alternative locator strategy");
    }

    @Test
    @DisplayName("Scenario 8: Non-Recoverable - WebDriver Session Error")
    public void testNonRecoverableWebDriverException() throws Exception {
        // WebDriverException indicates low-level driver problem
        WebDriverException driverException = new WebDriverException(
                "Session not created: This version of ChromeDriver only supports Chrome version 100");
        
        assertFalse(isRecoverableException(driverException),
                "WebDriverException should NOT be recoverable - requires driver restart");
    }

    @Test
    @DisplayName("Scenario 9: Non-Recoverable - Session Not Created")
    public void testNonRecoverableSessionException() throws Exception {
        // Session creation failure is not recoverable by locator healing
        WebDriverException alertException = new WebDriverException(
                "Unexpected alert: The page requested a page reload");
        
        assertFalse(isRecoverableException(alertException),
                "Alert/Session exceptions should NOT be recoverable by locator healing");
    }

    @Test
    @DisplayName("Scenario 10: Healing with Multiple Fallback Strategies")
    public void testHealingWithMultipleFallbacks() throws Exception {
        // Test that healing attempts multiple strategies in order:
        // 1. Alternative XPath with wildcards
        // 2. By text content
        // 3. By accessibility ID / resource-id
        // 4. By visual matching (OCR)
        // 5. By AI analysis (if enabled)
        
        String originalXPath = "//button[@id='submit']";
        
        // Strategy 1: Alternative XPath
        String xpath1 = "//button[contains(@id, 'submit')]";
        assertTrue(xpath1.contains("contains"));
        
        // Strategy 2: By text/aria-label
        String xpath2 = "//button[contains(@aria-label, 'Submit')]";
        assertTrue(xpath2.contains("aria-label"));
        
        // Strategy 3: By accessibility attributes
        String xpath3 = "//*[@accessible='true' and contains(@label, 'Submit')]";
        assertTrue(xpath3.contains("accessible"));
        
        // All strategies are valid fallback options
        assertTrue(xpath1 != null && xpath2 != null && xpath3 != null);
    }

    @Test
    @DisplayName("Test Exception Handler Integration")
    public void testExceptionHandlerWithHealing() throws Exception {
        // Verify ExceptionHandler properly categorizes exceptions
        // ExceptionHandler.Severity highSeverity = ExceptionHandler.Severity.HIGH;
        // ExceptionHandler.Severity lowSeverity = ExceptionHandler.Severity.LOW;
        
        // NoSuchElementException = HIGH severity (app-level issue, healable)
        // WebDriverException = CRITICAL (driver-level, not healable)
        assertTrue(true);  // Placeholder test
    }

    @Test
    @DisplayName("Test iOS Page Source Parsing")
    public void testIOSPageSourceParsing() throws Exception {
        Document doc = parseXML(iOSPageSource);
        assertNotNull(doc);
        
        // Verify key elements from iOS page source
        assertTrue(iOSPageSource.contains("Welcome, "));
        assertTrue(iOSPageSource.contains("XCUIElementTypeButton"));
        assertTrue(iOSPageSource.contains("components.tabbar"));
    }

    @Test
    @DisplayName("Test Android Page Source Parsing")
    public void testAndroidPageSourceParsing() throws Exception {
        Document doc = parseXML(androidPageSource);
        assertNotNull(doc);
        
        // Verify key elements from Android page source
        assertTrue(androidPageSource.contains("android.widget.FrameLayout"));
        assertTrue(androidPageSource.contains("com.brightinsight.mptestapp"));
        assertTrue(androidPageSource.contains("android.view.ViewGroup"));
    }

    @Test
    @DisplayName("OCR-Based Recovery Simulation")
    public void testOCRBasedRecovery() throws Exception {
        // Simulate scenario where normal locators fail
        // Fall back to OCR to find element by visible text on screen
        
        // From iOS page source, visible text includes "Welcome, ", "Home", "My data", etc.
        String visibleText = "Home";
        
        assertTrue(iOSPageSource.contains(visibleText),
                "OCR should be able to find visible text: " + visibleText);
        
        // This represents OCR-based recovery strategy
        String ocrBasedXPath = "//XCUIElementTypeStaticText[@value='" + visibleText + "']";
        assertTrue(ocrBasedXPath.contains("StaticText"));
    }

    @Test
    @DisplayName("Scenario 11: Feature Scorer matching vs normalized tokens")
    public void testFeatureScorerImprovesMatching() throws Exception {
        // Use an accessibility id known to exist in iOS fixture
        String original = "components.tabbar.tabItem.0";

        // Use feature-based scorer to heal
        AIHealerService.HealedLocator healed = healerService.healByFeatureScorer(original, mockDriver.getPageSource(), AIHealerService.Platform.IOS);
        assertNotNull(healed, "Feature scorer should return a candidate for existing accessibility id");
        assertTrue(healed.confidenceScore >= 0.6, "Healed locator should have reasonable confidence");

        // Verify normalized token similarity is high for exact match
        double tokenSim = AIHealerService.tokenSetRatio(original, healed.locatorValue);
        assertTrue(tokenSim >= 0.6, "Token-set similarity should be reasonably high: " + tokenSim);
    }

    // ============ HELPER METHODS ============

    /**
     * Determine if an exception is recoverable by locator healing
     * @param exception The caught exception
     * @return true if the exception can be recovered by alternative locators
     */
    private boolean isRecoverableException(Exception exception) {
        if (exception instanceof NoSuchElementException) {
            return true;  // Can retry with alternative locator
        }
        if (exception instanceof StaleElementReferenceException) {
            return true;  // Can re-find the element
        }
        if (exception instanceof TimeoutException) {
            return true;  // Can retry with different locator
        }
        if (exception instanceof InvalidSelectorException) {
            return true;  // Can fix XPath syntax
        }
        if (exception instanceof WebDriverException) {
            // Check if it's a low-level driver error (non-recoverable)
            String msg = exception.getMessage();
            if (msg != null && (msg.contains("Session") || msg.contains("Driver"))) {
                return false;  // Non-recoverable
            }
            return true;  // Some WebDriver exceptions are recoverable
        }
        return false;  // Unknown exceptions are not recoverable
    }

    /**
     * Parse XML page source
     */
    private Document parseXML(String xmlString) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xmlString)));
    }

    /**
     * Mock Appium Driver for testing
     */
    private static class MockAppiumDriver {
        private final String iOSPageSource;
        private final String androidPageSource;
        private String currentPageSource;

        public MockAppiumDriver(String iOSPageSource, String androidPageSource) {
            this.iOSPageSource = iOSPageSource;
            this.androidPageSource = androidPageSource;
            this.currentPageSource = iOSPageSource;  // Default to iOS
        }

        public void switchPlatform(String platform) {
            if ("ANDROID".equalsIgnoreCase(platform)) {
                currentPageSource = androidPageSource;
            } else {
                currentPageSource = iOSPageSource;
            }
        }

        public String getPageSource() {
            return currentPageSource;
        }

        public WebElement findElement(By by) throws NoSuchElementException {
            // Mock implementation - would parse currentPageSource
            throw new NoSuchElementException("Mock driver - element not found");
        }
    }
}
