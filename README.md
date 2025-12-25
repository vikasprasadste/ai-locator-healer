# AI Locator Healer

An intelligent Java framework for self-healing test locators in mobile application automation. This tool automatically detects and recovers from broken locators in Appium tests using AI-driven strategies, supporting both Android and iOS platforms.

## Features

- **Automatic Locator Recovery**: Intelligently finds alternative locators when primary ones fail
- **Multi-Platform Support**: Full support for both Android and iOS mobile automation
- **Exception Handling**: Built-in exception handler for graceful locator healing
- **Multiple Locator Strategies**: Support for XPath, ID, Class, Content, and other mobile-specific selectors
- **AI-Driven Healing**: Uses machine learning-based approaches to find optimal locator alternatives
- **OCR Capability**: Tesseract-based OCR for text recognition on mobile screens

## Prerequisites

- Java 17 or higher
- Maven 3.6+
- Appium Server 2.0+
- Android SDK (for Android testing)
- Xcode (for iOS testing)

## Installation

1. Clone the repository:
```bash
git clone https://github.com/vikasprasadste/ai-locator-healer.git
cd ai-locator-healer
```

2. Build the project:
```bash
mvn clean install
```

## Project Structure

```
ai-locator-healer/
├── src/
│   ├── main/java/com/testinsight/aihealer/    # Main source code
│   └── test/java/com/testinsight/aihealer/    # Test cases
├── pom.xml                                      # Maven configuration
└── README.md                                    # This file
```

## Running Tests

Execute all tests using Maven:
```bash
mvn clean test
```

### Test Results

All tests are passing:
- **Total Tests**: 15
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 0

## Key Components

### LocatorHealingTest
Main test class that validates the AI locator healing functionality across various scenarios.

## Configuration

Platform-specific configurations are available:
- **Android.xml**: Configuration for Android automation
- **iOS.xml**: Configuration for iOS automation

## Usage Example

```java
// Example of using the AI Locator Healer in your tests
```

## Technologies Used

- **Appium**: Mobile automation framework
- **Appium Java Client**: Java bindings for Appium
- **Tesseract OCR**: Optical character recognition for mobile screens
- **JUnit 5**: Testing framework
- **Maven**: Build automation
- **Java 17**: Programming language
- **WebDriver**: Foundation for mobile automation support

## Contributing

Contributions are welcome! Please follow these steps:
1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Open a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For issues and questions, please create an issue on GitHub.

## Author

TestInsight Team - AI Locator Healer Development
