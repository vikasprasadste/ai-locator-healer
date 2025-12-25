package com.testinsight.aihealer;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.json.JSONObject;
import org.openqa.selenium.By;
import org.openqa.selenium.Rectangle;
import org.w3c.dom.*;

import javax.imageio.ImageIO;
import javax.xml.parsers.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.xml.sax.InputSource;

public class AIHealerService extends AIBase{

    private static final double MINIMUM_SIMILARITY_THRESHOLD = 0.6;
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;
    
    // Timeout protection to prevent Appium session timeout
    private static final long MAX_HEALING_TIME_MS = 45000; // 45 seconds (well under 90s newCommandTimeout)
    private static final int MAX_NODES_TO_PROCESS = 1000; // Limit nodes processed per strategy

    /**
     * Platform detection enum
     */
    public enum Platform {
        IOS, ANDROID, UNKNOWN
    }

    /**
     * Container for healed locator with metadata
     */

    // ============ NORMALIZATION & FEATURE-BASED SCORER (PROTOTYPE) ============

    /**
     * Normalize a string for comparison: lowercase, remove punctuation, collapse whitespace
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.ROOT);
        // Replace non-alphanumeric with space
        n = n.replaceAll("[^a-z0-9]+", " ");
        n = n.trim().replaceAll("\\s+", " ");
        return n;
    }

    /**
     * Token-set ratio similar to fuzzywuzzy token_set_ratio
     */
    public static double tokenSetRatio(String a, String b) {
        if (a == null || b == null) return 0.0;
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() && nb.isEmpty()) return 1.0;
        if (na.isEmpty() || nb.isEmpty()) return 0.0;

        Set<String> sa = new LinkedHashSet<>(Arrays.asList(na.split(" ")));
        Set<String> sb = new LinkedHashSet<>(Arrays.asList(nb.split(" ")));

        Set<String> intersection = new LinkedHashSet<>(sa);
        intersection.retainAll(sb);

        Set<String> union = new LinkedHashSet<>(sa);
        union.addAll(sb);

        double inter = intersection.size();
        double uni = union.size();
        if (uni == 0) return 0.0;
        return inter / uni; // 0.0 - 1.0
    }

    /**
     * Compute a simple weighted feature score for an element relative to a target string.
     * Features: exact attribute matches (high weight), token similarity on text/label/name, visibility, class match.
     */
    private double computeFeatureScore(CachedElement ce, String target) {
        if (ce == null) return 0.0;
        double score = 0.0;
        // high-weight exact attribute matches
        if (target != null && !target.isEmpty()) {
            if (ce.resourceId != null && !ce.resourceId.isEmpty() && ce.resourceId.equalsIgnoreCase(target)) return 0.9;
            if (ce.contentDesc != null && !ce.contentDesc.isEmpty() && ce.contentDesc.equalsIgnoreCase(target)) return 0.9;
            if (ce.name != null && !ce.name.isEmpty() && ce.name.equalsIgnoreCase(target)) return 0.9;
        }

        // token similarity on textual attributes
        double textSim = Math.max(
                Math.max(tokenSetRatio(ce.text, target), tokenSetRatio(ce.label, target)),
                tokenSetRatio(ce.value, target)
        );
        score += 0.3 * textSim;

        // class/name similarity
        if (ce.className != null && target != null && !target.isEmpty()) {
            if (normalize(ce.className).contains(normalize(target))) score += 0.05;
        }

        // usability bonus
        if (ce.isUsable) score += 0.05;

        // cap score at 1.0
        return Math.min(1.0, score);
    }

    /**
     * Parse XML page source (helper for healer)
     */
    private Document parseXMLDocument(String xmlString) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xmlString)));
    }

    /**
     * Find best candidate element in page source using feature-based scoring.
     */
    public HealedLocator healByFeatureScorer(String originalLocatorValue, String pageSource, Platform platform) {
        if (pageSource == null || pageSource.isEmpty()) return null;
        try {
            Document doc = parseXMLDocument(pageSource);
            NodeList nodes = doc.getElementsByTagName("*");
            HealingContext ctx = new HealingContext();
            HealedLocator best = null;
            double bestScore = 0.0;

            for (int i = 0; i < nodes.getLength() && ctx.canProcessMoreNodes(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element)) continue;
                Element el = (Element) node;
                ctx.incrementNodes();

                CachedElement ce = new CachedElement(el, platform);
                double score = computeFeatureScore(ce, originalLocatorValue);
                if (score > bestScore) {
                    bestScore = score;
                    String strategy = "feature-scorer";
                    String value = ce.resourceId != null && !ce.resourceId.isEmpty() ? ce.resourceId :
                            (ce.contentDesc != null && !ce.contentDesc.isEmpty() ? ce.contentDesc : ce.text);
                    if (value == null || value.isEmpty()) value = ce.name != null ? ce.name : "";
                    HealedLocator candidate = new HealedLocator(strategy, value, score, platform);
                    best = candidate;
                }
            }

            if (best != null && best.confidenceScore >= MINIMUM_SIMILARITY_THRESHOLD) {
                best.originalValue = originalLocatorValue;
                best.originalStrategy = "original";
                return best;
            }
        } catch (Exception e) {
            System.out.println("Error in healByFeatureScorer: " + e.getMessage());
        }
        return null;
    }
    public static class HealedLocator {
        public String locatorStrategy;
        public String locatorValue;
        public double confidenceScore;
        public String originalStrategy;
        public String originalValue;
        public Platform platform;
        public List<String> fallbackOptions;

        public HealedLocator(String strategy, String value, double score, Platform platform) {
            this.locatorStrategy = strategy;
            this.locatorValue = value;
            this.confidenceScore = score;
            this.platform = platform;
            this.fallbackOptions = new ArrayList<>();
        }

        @Override
        public String toString() {
            return String.format("[%s] Strategy: %s, Value: %s, Confidence: %.2f%%",
                    platform, locatorStrategy, locatorValue, confidenceScore * 100);
        }
    }

    /**
     * Internal class to track healing progress and prevent timeout
     */
    private static class HealingContext {
        final long startTime;
        final long maxEndTime;
        int nodesProcessed = 0;
        boolean timeoutReached = false;

        HealingContext() {
            this.startTime = System.currentTimeMillis();
            this.maxEndTime = startTime + MAX_HEALING_TIME_MS;
        }

        boolean hasTimeRemaining() {
            if (System.currentTimeMillis() >= maxEndTime) {
                timeoutReached = true;
                return false;
            }
            return true;
        }

        boolean canProcessMoreNodes() {
            return nodesProcessed < MAX_NODES_TO_PROCESS && hasTimeRemaining();
        }

        void incrementNodes() {
            nodesProcessed++;
        }

        long getElapsedTime() {
            return System.currentTimeMillis() - startTime;
        }
    }
    
    /**
     * Cached element data to avoid re-parsing XML for each strategy
     */
    private static class CachedElement {
        Element element;
        String resourceId;
        String contentDesc;
        String text;
        String name;
        String label;
        String value;
        String type;
        String className;
        boolean isUsable;

        CachedElement(Element el, Platform platform) {
            this.element = el;
            this.resourceId = el.getAttribute("resource-id");
            this.contentDesc = el.getAttribute("content-desc");
            this.text = el.getAttribute("text");
            this.name = el.getAttribute("name");
            this.label = el.getAttribute("label");
            this.value = el.getAttribute("value");
            this.type = el.getAttribute("type");
            this.className = el.getAttribute("class");
            this.isUsable = checkUsability(el, platform);
        }

        private boolean checkUsability(Element el, Platform platform) {
            String visible = el.getAttribute("visible");
            String enabled = el.getAttribute("enabled");

            if (platform == Platform.ANDROID) {
                String displayed = el.getAttribute("displayed");
                return !"false".equalsIgnoreCase(enabled) &&
                        !"false".equalsIgnoreCase(displayed);
            } else if (platform == Platform.IOS) {
                return !"false".equalsIgnoreCase(visible) &&
                        !"false".equalsIgnoreCase(enabled);
            }

            return true;
        }
    }

    /**
     * Cached healed locator with metadata and usage statistics
     */
    public static class CachedHealedLocator {
        public HealedLocator healedLocator;
        public long timestamp;
        public int useCount;
        public int successCount;
        public int failureCount;
        public String cacheKey;
        public long lastUsedTime;

        public CachedHealedLocator(HealedLocator healedLocator, String cacheKey) {
            this.healedLocator = healedLocator;
            this.cacheKey = cacheKey;
            this.timestamp = System.currentTimeMillis();
            this.lastUsedTime = timestamp;
            this.useCount = 0;
            this.successCount = 0;
            this.failureCount = 0;
        }

        public void recordUse(boolean success) {
            this.useCount++;
            this.lastUsedTime = System.currentTimeMillis();
            if (success) {
                this.successCount++;
            } else {
                this.failureCount++;
            }
        }

        public double getSuccessRate() {
            return useCount == 0 ? 0.0 : (double) successCount / useCount;
        }

        public boolean isExpired(long ttlMillis) {
            return (System.currentTimeMillis() - timestamp) > ttlMillis;
        }

        public boolean isUnreliable() {
            // Consider unreliable if: used 3+ times and success rate < 50%
            return useCount >= 3 && getSuccessRate() < 0.5;
        }

        @Override
        public String toString() {
            return String.format("Cached[%s, Uses: %d, Success: %.1f%%, Age: %ds]",
                    healedLocator, useCount, getSuccessRate() * 100,
                    (System.currentTimeMillis() - timestamp) / 1000);
        }
    }

    /**
     * Locator Cache Manager - In-Memory Cache with Automatic Invalidation
     */
    public static class LocatorCache {
        private static final long DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes
        private static final int MAX_CACHE_SIZE = 500;
        
        private final Map<String, CachedHealedLocator> cache = new LinkedHashMap<String, CachedHealedLocator>(100, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedHealedLocator> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };
        
        private long ttlMillis = DEFAULT_TTL_MS;
        private boolean enabled = true;

        /**
         * Generate cache key from original locator
         */
        public String generateCacheKey(String locatorType, String locatorValue, String locatorKey) {
            String key = locatorType + "||" + locatorValue;
            if (locatorKey != null && !locatorKey.isEmpty()) {
                key += "||KEY:" + locatorKey;
            }
            return key;
        }

        /**
         * Get cached healed locator
         */
        public CachedHealedLocator get(String cacheKey) {
            if (!enabled) return null;
            
            CachedHealedLocator cached = cache.get(cacheKey);
            if (cached == null) return null;
            
            // Remove if expired
            if (cached.isExpired(ttlMillis)) {
                cache.remove(cacheKey);
                System.out.println("🗑️  Cache expired: " + cacheKey);
                return null;
            }
            
            // Remove if unreliable
            if (cached.isUnreliable()) {
                cache.remove(cacheKey);
                System.out.println("🗑️  Cache invalidated (unreliable): " + cacheKey + 
                                 " (Success rate: " + (cached.getSuccessRate() * 100) + "%)");
                return null;
            }
            
            return cached;
        }

        /**
         * Put healed locator into cache
         */
        public void put(String cacheKey, HealedLocator healedLocator) {
            if (!enabled) return;
            
            CachedHealedLocator cached = new CachedHealedLocator(healedLocator, cacheKey);
            cache.put(cacheKey, cached);
            System.out.println("💾 Cached healed locator: " + cacheKey);
        }

        /**
         * Record usage of cached locator
         */
        public void recordUsage(String cacheKey, boolean success) {
            CachedHealedLocator cached = cache.get(cacheKey);
            if (cached != null) {
                cached.recordUse(success);
                if (!success) {
                    System.out.println("📊 Cache usage recorded: " + cacheKey + 
                                     " | Success: " + success + 
                                     " | Rate: " + String.format("%.1f%%", cached.getSuccessRate() * 100));
                }
            }
        }

        /**
         * Invalidate specific cache entry
         */
        public void invalidate(String cacheKey) {
            if (cache.remove(cacheKey) != null) {
                System.out.println("🗑️  Cache invalidated: " + cacheKey);
            }
        }

        /**
         * Clear all cache entries
         */
        public void clear() {
            int size = cache.size();
            cache.clear();
            System.out.println("🗑️  Cache cleared: " + size + " entries removed");
        }

        /**
         * Get cache statistics
         */
        public Map<String, Object> getStats() {
            Map<String, Object> stats = new HashMap<>();
            stats.put("size", cache.size());
            stats.put("maxSize", MAX_CACHE_SIZE);
            stats.put("ttlMs", ttlMillis);
            stats.put("enabled", enabled);
            
            int totalUses = 0;
            int totalSuccesses = 0;
            for (CachedHealedLocator cached : cache.values()) {
                totalUses += cached.useCount;
                totalSuccesses += cached.successCount;
            }
            stats.put("totalUses", totalUses);
            stats.put("totalSuccesses", totalSuccesses);
            stats.put("overallSuccessRate", totalUses > 0 ? (double) totalSuccesses / totalUses : 0.0);
            
            return stats;
        }

        public void setTtl(long ttlMillis) {
            this.ttlMillis = ttlMillis;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (!enabled) {
                clear();
            }
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    // Global cache instance
    private static final LocatorCache locatorCache = new LocatorCache();
    
    // Global report instance
    private static final HealingReport healingReport = new HealingReport();
    
    /**
     * Get the global locator cache instance
     */
    public static LocatorCache getCache() {
        return locatorCache;
    }
    
    /**
     * Get the global healing report instance
     */
    public static HealingReport getReport() {
        return healingReport;
    }

    /**
     * Healing Report - Collects all healing events for JSON export
     */
    public static class HealingReport {
        private final List<HealingEvent> healingEvents = new ArrayList<>();
        private long sessionStartTime = System.currentTimeMillis();
        
        /**
         * Record a healing event
         */
        public synchronized void recordHealing(HealingEvent event) {
            healingEvents.add(event);
        }
        
        /**
         * Get all healing events
         */
        public synchronized List<HealingEvent> getHealingEvents() {
            return new ArrayList<>(healingEvents);
        }
        
        /**
         * Generate simplified JSON report (only essential information)
         */
        public synchronized String generateSimplifiedJsonReport() {
            try {
                JSONObject report = new JSONObject();
                JSONObject healedLocatorsArray = new JSONObject();
                
                // Group events by cache key
                Map<String, HealingEvent> uniqueLocators = new LinkedHashMap<>();
                Map<String, Integer> cacheHitCounts = new HashMap<>();
                
                for (HealingEvent event : healingEvents) {
                    String key = event.getCacheKey();
                    
                    // Track cache hits
                    if (event.fromCache) {
                        cacheHitCounts.merge(key, 1, Integer::sum);
                    }
                    
                    // Keep first occurrence for details
                    if (!uniqueLocators.containsKey(key)) {
                        uniqueLocators.put(key, event);
                    }
                }
                
                // Build simplified report
                int index = 1;
                for (Map.Entry<String, HealingEvent> entry : uniqueLocators.entrySet()) {
                    HealingEvent event = entry.getValue();
                    JSONObject locatorInfo = new JSONObject();
                    
                    // Original locator
                    locatorInfo.put("original", event.originalLocatorType + "||" + event.originalLocatorValue);
                    
                    // Healed value
                    String healedValue = event.healedLocatorType != null && event.healedLocatorValue != null
                        ? event.healedLocatorType + "||" + event.healedLocatorValue
                        : "Not healed";
                    locatorInfo.put("healedValue", healedValue);
                    
                    // Suggestions (alternatives)
                    if (event.alternatives != null && !event.alternatives.isEmpty()) {
                        locatorInfo.put("suggestions", event.alternatives);
                    } else {
                        locatorInfo.put("suggestions", new ArrayList<>());
                    }
                    
                    // Cache hits
                    int cacheHits = cacheHitCounts.getOrDefault(entry.getKey(), 0);
                    locatorInfo.put("cacheHits", cacheHits);
                    
                    // Success status
                    locatorInfo.put("success", event.success);
                    
                    healedLocatorsArray.put("locator_" + index, locatorInfo);
                    index++;
                }
                
                report.put("healedLocators", healedLocatorsArray);
                report.put("totalUniqueLocators", uniqueLocators.size());
                
                return report.toString(2);
            } catch (Exception e) {
                e.printStackTrace();
                return "{\"error\": \"Failed to generate simplified report: " + e.getMessage() + "\"}";
            }
        }
        
        /**
         * Generate full JSON report (verbose with all details)
         */
        public synchronized String generateJsonReport() {
            try {
                JSONObject report = new JSONObject();
            
            // Session info
            report.put("sessionStartTime", sessionStartTime);
            report.put("reportGeneratedTime", System.currentTimeMillis());
            report.put("sessionDurationSeconds", (System.currentTimeMillis() - sessionStartTime) / 1000);
            
            // Statistics
            JSONObject stats = new JSONObject();
            stats.put("totalHealingAttempts", healingEvents.size());
            
            int successfulHeals = 0;
            int fromCache = 0;
            int fromLocalHeuristic = 0;
            int fromAI = 0;
            
            Map<String, Integer> cacheHitCounts = new HashMap<>();
            Set<String> uniqueLocators = new HashSet<>();
            
            for (HealingEvent event : healingEvents) {
                if (event.success) successfulHeals++;
                if (event.fromCache) {
                    fromCache++;
                    cacheHitCounts.merge(event.getCacheKey(), 1, Integer::sum);
                }
                if ("local".equals(event.healingMethod)) fromLocalHeuristic++;
                if ("ai".equals(event.healingMethod)) fromAI++;
                uniqueLocators.add(event.getCacheKey());
            }
            
            stats.put("successfulHeals", successfulHeals);
            stats.put("failedHeals", healingEvents.size() - successfulHeals);
            stats.put("uniqueLocatorsFailed", uniqueLocators.size());
            stats.put("cacheHits", fromCache);
            stats.put("cacheHitRate", healingEvents.isEmpty() ? 0.0 : (double) fromCache / healingEvents.size());
            stats.put("healedViaLocalHeuristic", fromLocalHeuristic);
            stats.put("healedViaAI", fromAI);
            
            report.put("statistics", stats);
            
            // Cache statistics
            JSONObject cacheStats = new JSONObject(locatorCache.getStats());
            report.put("cacheStatistics", cacheStats);
            
            // Cache hit counts per locator
            JSONObject cacheHits = new JSONObject();
            for (Map.Entry<String, Integer> entry : cacheHitCounts.entrySet()) {
                cacheHits.put(entry.getKey(), entry.getValue());
            }
            report.put("cacheHitsPerLocator", cacheHits);
            
            // Healing events
            JSONObject healedLocators = new JSONObject();
            for (HealingEvent event : healingEvents) {
                String key = event.getCacheKey();
                if (!healedLocators.has(key)) {
                    healedLocators.put(key, event.toJson());
                } else {
                    // Update with latest event data
                    JSONObject existing = healedLocators.getJSONObject(key);
                    existing.put("totalAttempts", existing.getInt("totalAttempts") + 1);
                    if (event.fromCache) {
                        existing.put("cacheHits", existing.getInt("cacheHits") + 1);
                    }
                }
            }
            report.put("healedLocators", healedLocators);
            
            // Detailed events (chronological)
            JSONObject detailedEvents = new JSONObject();
            for (int i = 0; i < healingEvents.size(); i++) {
                detailedEvents.put("event_" + (i + 1), healingEvents.get(i).toDetailedJson());
            }
                report.put("detailedEvents", detailedEvents);
                
                return report.toString(2); // Pretty print with 2-space indent
            } catch (Exception e) {
                e.printStackTrace();
                return "{\"error\": \"Failed to generate report: " + e.getMessage() + "\"}";
            }
        }
        
        /**
         * Save simplified JSON report to file (default)
         */
        public synchronized void saveToFile(String filePath) {
            saveToFile(filePath, true);
                printSummary();
        }

        /**
         * Save JSON report to file with option to choose simplified or full format
         * @param filePath Path to save the report
         * @param simplified If true, generates simplified report; if false, generates full verbose report
         */
        public synchronized void saveToFile(String filePath, boolean simplified) {
            try {
                String json = simplified ? generateSimplifiedJsonReport() : generateJsonReport();
                Files.write(new File(filePath).toPath(), json.getBytes());
                String formatType = simplified ? "simplified" : "full";
                System.out.println("📄 Healing report (" + formatType + " format) saved to: " + filePath);
            } catch (Exception e) {
                System.err.println("❌ Failed to save healing report: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        /**
         * Clear all healing events
         */
        public synchronized void clear() {
            healingEvents.clear();
            sessionStartTime = System.currentTimeMillis();
            System.out.println("🗑️  Healing report cleared");
        }
        
        /**
         * Print summary to console
         */
        public synchronized void printSummary() {
            System.out.println("\n" + "=".repeat(60));
            System.out.println("📊 HEALING REPORT SUMMARY");
            System.out.println("=".repeat(60));
            
            int total = healingEvents.size();
            int successful = (int) healingEvents.stream().filter(e -> e.success).count();
            int fromCache = (int) healingEvents.stream().filter(e -> e.fromCache).count();
            Set<String> unique = new HashSet<>();
            healingEvents.forEach(e -> unique.add(e.getCacheKey()));
            
            System.out.println("Total Healing Attempts: " + total);
            System.out.println("Successful Heals: " + successful);
            System.out.println("Failed Heals: " + (total - successful));
            System.out.println("Unique Locators Failed: " + unique.size());
            System.out.println("Cache Hits: " + fromCache);
            System.out.println("Cache Hit Rate: " + String.format("%.1f%%", 
                total > 0 ? (double) fromCache / total * 100 : 0));
            System.out.println("=".repeat(60));
            System.out.println("💡 To save report:");
            System.out.println("   Simplified: saveToFile(\"path\") or saveToFile(\"path\", true)");
            System.out.println("   Full:       saveToFile(\"path\", false)");
            System.out.println("=".repeat(60) + "\n");
        }
    }
    
    /**
     * Individual healing event
     */
    public static class HealingEvent {
        public String originalLocatorType;
        public String originalLocatorValue;
        public String originalLocatorKey;
        public String healedLocatorType;
        public String healedLocatorValue;
        public double confidenceScore;
        public List<String> alternatives;
        public boolean fromCache;
        public boolean success;
        public String healingMethod; // "local", "ai", or "cache"
        public long timestamp;
        public long healingTimeMs;
        public String platform;
        public String testName;
        public String actionDescription;
        
        public HealingEvent() {
            this.timestamp = System.currentTimeMillis();
            this.alternatives = new ArrayList<>();
        }
        
        public String getCacheKey() {
            String key = originalLocatorType + "||" + originalLocatorValue;
            if (originalLocatorKey != null && !originalLocatorKey.isEmpty()) {
                key += "||KEY:" + originalLocatorKey;
            }
            return key;
        }
        
        public JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("originalLocator", new JSONObject()
                    .put("type", originalLocatorType)
                    .put("value", originalLocatorValue)
                    .put("key", originalLocatorKey != null ? originalLocatorKey : ""));
                
                json.put("healedLocator", new JSONObject()
                    .put("type", healedLocatorType != null ? healedLocatorType : "")
                    .put("value", healedLocatorValue != null ? healedLocatorValue : "")
                    .put("confidence", String.format("%.2f%%", confidenceScore * 100)));
                
                json.put("alternatives", alternatives != null ? alternatives : new ArrayList<>());
                json.put("fromCache", fromCache);
                json.put("success", success);
                json.put("healingMethod", healingMethod != null ? healingMethod : "unknown");
                json.put("platform", platform != null ? platform : "unknown");
                json.put("totalAttempts", 1);
                json.put("cacheHits", fromCache ? 1 : 0);
                
                return json;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
        
        public JSONObject toDetailedJson() {
            try {
                JSONObject json = toJson();
                json.put("timestamp", timestamp);
                json.put("healingTimeMs", healingTimeMs);
                json.put("testName", testName != null ? testName : "");
                json.put("actionDescription", actionDescription != null ? actionDescription : "");
                return json;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    /**
     * Enhanced version that returns HealedLocator with alternatives for retry logic
     */
    public static HealedLocator findClosestMatchWithAlternatives(String locatorType, String locatorValue, String currentPageSource) {
        return findClosestMatchWithAlternatives(locatorType, locatorValue, currentPageSource, null);
    }

    /**
     * Enhanced version with locator key support and caching
     */
    public static HealedLocator findClosestMatchWithAlternatives(String locatorType, String locatorValue, 
                                                                 String currentPageSource, String locatorKey) {
        // 🔍 Step 1: Check cache first
        String cacheKey = locatorCache.generateCacheKey(locatorType, locatorValue, locatorKey);
        CachedHealedLocator cached = locatorCache.get(cacheKey);
        
        if (cached != null) {
            System.out.println("💨 Using cached healed locator: " + cached);
            return cached.healedLocator;
        }
        
        // 🔧 Step 2: Try local heuristic (cache miss)
        HealedLocator localResult = findClosestMatchLocal(locatorType, locatorValue, currentPageSource, locatorKey);
        if (localResult != null && localResult.confidenceScore >= MINIMUM_SIMILARITY_THRESHOLD) {
            System.out.println("✓ Healed locator via local heuristic: " + localResult);
            System.out.println("  Confidence: " + (localResult.confidenceScore * 100) + "%");
            
            // Print alternatives if available
            if (!localResult.fallbackOptions.isEmpty()) {
                System.out.println("  Alternative locators found (" + localResult.fallbackOptions.size() + " alternatives):");
                for (int i = 0; i < Math.min(3, localResult.fallbackOptions.size()); i++) {
                    System.out.println("    " + (i+1) + ". " + localResult.fallbackOptions.get(i));
                }
                if (localResult.fallbackOptions.size() > 3) {
                    System.out.println("    ... and " + (localResult.fallbackOptions.size() - 3) + " more");
                }
            }
            
            // 💾 Cache the successful healing
            locatorCache.put(cacheKey, localResult);
            return localResult;
        }

        // 🤖 Step 3: Fallback to AI model (returns String, wrap in HealedLocator)
        String aiResult = findClosestMatchAI(locatorType, locatorValue, currentPageSource);
        if (aiResult != null) {
            System.out.println("✓ Healed locator via AI model: " + aiResult);
            // Wrap AI result in HealedLocator object
            HealedLocator aiLocator = new HealedLocator("xpath", aiResult, 0.7, Platform.UNKNOWN);
            aiLocator.originalStrategy = locatorType;
            aiLocator.originalValue = locatorValue;
            
            // 💾 Cache the AI healing result
            locatorCache.put(cacheKey, aiLocator);
            return aiLocator;
        } else {
            System.err.println("✗ AI recovery failed for locator: " + locatorValue);
        }

        return null;
    }

    /**
     * Enhanced local heuristic: Multi-strategy XML parsing + platform-aware matching
     * OPTIMIZED VERSION with timeout protection and element caching
     * (Overload without locatorKey for backward compatibility)
     */
    private static HealedLocator findClosestMatchLocal(String locatorType, String locatorValue, String currentPageSource) {
        return findClosestMatchLocal(locatorType, locatorValue, currentPageSource, null);
    }

    /**
     * Enhanced local heuristic with locator key support
     * OPTIMIZED VERSION with timeout protection and element caching
     * 
     * @param locatorType Original locator type
     * @param locatorValue Original locator value (can be empty/blank)
     * @param currentPageSource Page source XML
     * @param locatorKey Optional property key (e.g., "login.input.email_address")
     * @return HealedLocator or null
     */
    private static HealedLocator findClosestMatchLocal(String locatorType, String locatorValue, 
                                                       String currentPageSource, String locatorKey) {
        // Check if we should use locator key instead of value
        boolean useKeyFallback = (locatorValue == null || locatorValue.trim().isEmpty()) && 
                                 (locatorKey != null && !locatorKey.trim().isEmpty());
        
        if (useKeyFallback) {
            System.out.println("⚠️  Locator value is empty/blank - using locator key for healing: " + locatorKey);
            locatorValue = extractSearchTermsFromKey(locatorKey);
            System.out.println("🔑 Extracted search terms from key: " + locatorValue);
        }
        
        if (locatorValue == null || locatorValue.isEmpty() || currentPageSource == null) {
            return null;
        }

        // Initialize healing context for timeout protection
        HealingContext context = new HealingContext();
        System.out.println("⏱️  Starting locator healing (timeout: " + MAX_HEALING_TIME_MS/1000 + "s)...");

        try {
            // Parse XML once
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputSource is = new InputSource(new StringReader(currentPageSource));
            Document doc = builder.parse(is);

            // Detect platform
            Platform platform = detectPlatform(currentPageSource);
            System.out.println("🔍 Detected Platform: " + platform);

            // Parse all elements ONCE and cache them
            NodeList allNodes = doc.getElementsByTagName("*");
            List<CachedElement> cachedElements = new ArrayList<>();
            
            System.out.println("📋 Caching " + allNodes.getLength() + " elements for fast processing...");
            for (int i = 0; i < allNodes.getLength() && context.canProcessMoreNodes(); i++) {
                Element el = (Element) allNodes.item(i);
                cachedElements.add(new CachedElement(el, platform));
                context.incrementNodes();
            }
            
            if (context.timeoutReached) {
                System.err.println("⚠️  Timeout reached during element caching. Processed " + 
                                 cachedElements.size() + " elements in " + context.getElapsedTime() + "ms");
                return null;
            }
            
            System.out.println("✓ Cached " + cachedElements.size() + " elements in " + 
                             context.getElapsedTime() + "ms");

            // Try multiple healing strategies using cached data
            HealedLocator result = null;

            // Strategy 1: Find exact or near-exact match with same locator type
            if (context.hasTimeRemaining()) {
                result = findBySameStrategyOptimized(cachedElements, locatorType, locatorValue, platform, context);
                if (result != null && result.confidenceScore >= HIGH_CONFIDENCE_THRESHOLD) {
                    System.out.println("  Strategy 1 (Same Strategy): SUCCESS - Confidence " + 
                                     (result.confidenceScore * 100) + "% [" + context.getElapsedTime() + "ms]");
                    addAlternativeLocators(result, doc, platform);
                    return result;
                }
            }

            // Strategy 2: Find by partial match (for complex locators with dynamic parts)
            if (context.hasTimeRemaining() && 
                (result == null || result.confidenceScore < MINIMUM_SIMILARITY_THRESHOLD)) {
                HealedLocator partialResult = findByPartialMatchOptimized(
                    cachedElements, locatorType, locatorValue, platform, context);
                if (partialResult != null && 
                    (result == null || partialResult.confidenceScore > result.confidenceScore)) {
                    result = partialResult;
                    System.out.println("  Strategy 2 (Partial Match): SUCCESS - Confidence " + 
                                     (result.confidenceScore * 100) + "% [" + context.getElapsedTime() + "ms]");
                }
            }

            // Strategy 3: Find by alternative attributes (cross-attribute matching)
            if (context.hasTimeRemaining() && 
                (result == null || result.confidenceScore < MINIMUM_SIMILARITY_THRESHOLD)) {
                HealedLocator altResult = findByAlternativeAttributesOptimized(
                    cachedElements, locatorValue, platform, context);
                if (altResult != null && 
                    (result == null || altResult.confidenceScore > result.confidenceScore)) {
                    result = altResult;
                    System.out.println("  Strategy 3 (Alternative Attributes): SUCCESS - Confidence " + 
                                     (result.confidenceScore * 100) + "% [" + context.getElapsedTime() + "ms]");
                }
            }

            // Strategy 4: Find by context (siblings, parent attributes) - SKIP if time is low
            if (context.hasTimeRemaining() && 
                (result == null || result.confidenceScore < MINIMUM_SIMILARITY_THRESHOLD)) {
                // Context-based is most expensive, only run if we have time
                long remainingTime = context.maxEndTime - System.currentTimeMillis();
                if (remainingTime > 5000) { // Need at least 5 seconds remaining
                    HealedLocator contextResult = findByContextOptimized(
                        doc, cachedElements, locatorType, locatorValue, platform, context);
                    if (contextResult != null && 
                        (result == null || contextResult.confidenceScore > result.confidenceScore)) {
                        result = contextResult;
                        System.out.println("  Strategy 4 (Context-Based): SUCCESS - Confidence " + 
                                         (result.confidenceScore * 100) + "% [" + context.getElapsedTime() + "ms]");
                    }
                } else {
                    System.out.println("  Strategy 4 (Context-Based): SKIPPED - Insufficient time remaining");
                }
            }

            // Store original for reference and add alternatives
            if (result != null) {
                result.originalStrategy = locatorType;
                result.originalValue = locatorValue;
                System.out.println("⏱️  Total healing time: " + context.getElapsedTime() + "ms");
                addAlternativeLocators(result, doc, platform);
            } else if (context.timeoutReached) {
                System.err.println("⚠️  Healing timeout reached after " + context.getElapsedTime() + "ms");
            } else {
                System.out.println("⏱️  Healing completed in " + context.getElapsedTime() + "ms (no match found)");
            }

            return result;

        } catch (Exception e) {
            System.err.println("❌ Error during locator healing: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Calls AI model (OpenAI or internal MCP) for semantic locator healing
     */
    private static String findClosestMatchAI(String locatorType, String locatorValue, String currentPageSource) {
        try {
            if (AI_API_KEY == null || AI_API_KEY.isEmpty()) {
                System.err.println("No API key provided for AI healing.");
                return null;
            }

            // Build a descriptive input for AI
            String attributeHint;
            switch (locatorType.toLowerCase()) {
                case "id":
                    attributeHint = "resource-id";
                    break;
                case "accessibility":
                case "aid":
                    attributeHint = "content-desc";
                    break;
                case "name":
                    attributeHint = "text";
                    break;
                case "classname":
                case "cn":
                case "class-chain":
                case "cc":
                    attributeHint = "class/tag hierarchy";
                    break;
                case "predicate":
                    attributeHint = "iOS label, value, name attributes";
                    break;
                case "uiautomator":
                    attributeHint = "Android resource-id and text";
                    break;
                case "xpath":
                    attributeHint = "XPath strings";
                    break;
                case "image":
                    attributeHint = "Visual matching (use screenshot)";
                    break;
                default:
                    attributeHint = "text, content-desc, resource-id";
            }

            JSONObject body = new JSONObject();
            body.put("model", AI_CHAT_MODEL);
            body.put("input",
                    "You are an AI mobile automation assistant.\n" +
                            "The failed locator type is: " + locatorType + " (" + attributeHint + ")\n" +
                            "The original locator value is: " + locatorValue + "\n" +
                            "Here is the current Appium page source XML:\n" + currentPageSource + "\n" +
                            "Please provide the most likely locator to locate the element in this page source.");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.openai.com/v1/responses"))
                    .header("Authorization", "Bearer " + AI_API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONObject result = new JSONObject(response.body());
            String healedLocator = result.optString("output", null); // adapt to actual API response structure
            if (healedLocator != null && !healedLocator.isEmpty()) {
                System.out.println("AI suggested healed locator: " + healedLocator);
            }

            return healedLocator;

        } catch (Exception e) {
            System.err.println("AI healing call failed: " + e.getMessage());
            return null;
        }
    }


    // ------------------ OPTIMIZED STRATEGY METHODS (with caching) ------------------

    /**
     * Optimized Strategy 1: Find by same locator strategy using cached elements
     */
    private static HealedLocator findBySameStrategyOptimized(
            List<CachedElement> cachedElements, String locatorType, String locatorValue, 
            Platform platform, HealingContext context) {

        Map<Element, Double> candidates = new HashMap<>();
        String normalizedType = normalizeLocatorType(locatorType);

        for (CachedElement cached : cachedElements) {
            if (!context.canProcessMoreNodes()) break;
            if (!cached.isUsable) continue;

            String attributeValue = extractAttributeFromCached(cached, normalizedType, platform);

            if (attributeValue != null && !attributeValue.trim().isEmpty()) {
                double score = calculateSimilarity(locatorValue, attributeValue);

                // Boost score for exact substring matches
                if (attributeValue.contains(locatorValue) || locatorValue.contains(attributeValue)) {
                    score = Math.min(1.0, score + 0.15);
                }

                if (score >= MINIMUM_SIMILARITY_THRESHOLD) {
                    candidates.put(cached.element, score);
                }
            }
            context.incrementNodes();
        }

        return candidates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> createHealedLocator(entry.getKey(), normalizedType,
                        entry.getValue(), platform))
                .orElse(null);
    }

    /**
     * Optimized Strategy 2: Partial match using cached elements
     */
    private static HealedLocator findByPartialMatchOptimized(
            List<CachedElement> cachedElements, String locatorType, String locatorValue,
            Platform platform, HealingContext context) {

        Map<Element, Double> candidates = new HashMap<>();
        String keyPart = extractKeyPart(locatorValue);
        String normalizedType = normalizeLocatorType(locatorType);

        if (keyPart.length() < 3) {
            return null;
        }

        for (CachedElement cached : cachedElements) {
            if (!context.canProcessMoreNodes()) break;
            if (!cached.isUsable) continue;

            String attributeValue = extractAttributeFromCached(cached, normalizedType, platform);

            if (attributeValue != null && !attributeValue.trim().isEmpty()) {
                String candidateKeyPart = extractKeyPart(attributeValue);

                if (candidateKeyPart.toLowerCase().contains(keyPart.toLowerCase()) ||
                        keyPart.toLowerCase().contains(candidateKeyPart.toLowerCase())) {

                    double score = calculatePartialMatchScore(locatorValue, attributeValue);

                    if (score >= MINIMUM_SIMILARITY_THRESHOLD) {
                        candidates.put(cached.element, score);
                    }
                }
            }
            context.incrementNodes();
        }

        return candidates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> createHealedLocator(entry.getKey(), normalizedType,
                        entry.getValue(), platform))
                .orElse(null);
    }

    /**
     * Optimized Strategy 3: Alternative attributes using cached elements
     */
    private static HealedLocator findByAlternativeAttributesOptimized(
            List<CachedElement> cachedElements, String locatorValue,
            Platform platform, HealingContext context) {

        Map<Element, AttributeMatch> candidates = new HashMap<>();

        for (CachedElement cached : cachedElements) {
            if (!context.canProcessMoreNodes()) break;
            if (!cached.isUsable) continue;

            List<AttributeMatch> matches = new ArrayList<>();

            if (platform == Platform.IOS) {
                matches.add(checkAttributeCached(cached, "name", locatorValue, "accessibility"));
                matches.add(checkAttributeCached(cached, "label", locatorValue, "name"));
                matches.add(checkAttributeCached(cached, "value", locatorValue, "name"));
                matches.add(checkAttributeCached(cached, "type", locatorValue, "classname"));
            } else if (platform == Platform.ANDROID) {
                matches.add(checkAttributeCached(cached, "resource-id", locatorValue, "id"));
                matches.add(checkAttributeCached(cached, "content-desc", locatorValue, "accessibility"));
                matches.add(checkAttributeCached(cached, "text", locatorValue, "name"));
                matches.add(checkAttributeCached(cached, "class", locatorValue, "classname"));
            }

            AttributeMatch bestMatch = matches.stream()
                    .filter(m -> m.score >= MINIMUM_SIMILARITY_THRESHOLD)
                    .max(Comparator.comparingDouble(m -> m.score))
                    .orElse(null);

            if (bestMatch != null) {
                candidates.put(cached.element, bestMatch);
            }
            context.incrementNodes();
        }

        return candidates.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().score))
                .map(entry -> {
                    AttributeMatch match = entry.getValue();
                    return new HealedLocator(match.strategy, match.attributeValue, match.score, platform);
                })
                .orElse(null);
    }

    /**
     * Optimized Strategy 4: Context-based using cached elements
     */
    private static HealedLocator findByContextOptimized(
            Document doc, List<CachedElement> cachedElements, String locatorType, 
            String locatorValue, Platform platform, HealingContext context) {

        Map<Element, Double> candidates = new HashMap<>();
        String[] keywords = locatorValue.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .split("\\s+");

        for (CachedElement cached : cachedElements) {
            if (!context.canProcessMoreNodes()) break;
            if (!cached.isUsable) continue;

            StringBuilder contextStr = new StringBuilder();
            contextStr.append(getAllAttributesCached(cached, platform)).append(" ");

            // Add parent context
            Node parent = cached.element.getParentNode();
            if (parent instanceof Element) {
                contextStr.append(getAllAttributes((Element) parent, platform)).append(" ");
            }

            // Add sibling context (lightweight)
            Node prevSibling = getPreviousElementSibling(cached.element);
            if (prevSibling instanceof Element) {
                contextStr.append(((Element) prevSibling).getAttribute("text")).append(" ");
            }

            String contextLower = contextStr.toString().toLowerCase();
            int matchCount = 0;
            for (String keyword : keywords) {
                if (keyword.length() > 2 && contextLower.contains(keyword)) {
                    matchCount++;
                }
            }

            if (keywords.length > 0) {
                double score = (double) matchCount / keywords.length;
                if (score >= MINIMUM_SIMILARITY_THRESHOLD) {
                    candidates.put(cached.element, score);
                }
            }
            context.incrementNodes();
        }

        return candidates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> createHealedLocator(entry.getKey(), "xpath",
                        entry.getValue(), platform))
                .orElse(null);
    }

    /**
     * Extract attribute value from cached element
     */
    private static String extractAttributeFromCached(CachedElement cached, String strategy, Platform platform) {
        if (platform == Platform.IOS) {
            switch (strategy) {
                case "id":
                case "accessibility":
                    return cached.name;
                case "name":
                    return (cached.label != null && !cached.label.isEmpty()) ? cached.label : cached.value;
                case "classname":
                    return cached.type;
                case "text":
                    return cached.value;
                default:
                    return cached.name;
            }
        } else if (platform == Platform.ANDROID) {
            switch (strategy) {
                case "id":
                    return cached.resourceId;
                case "accessibility":
                    return cached.contentDesc;
                case "name":
                case "text":
                    return cached.text;
                case "classname":
                    return cached.className;
                default:
                    return cached.contentDesc;
            }
        }

        String value = cached.name;
        if (value == null || value.isEmpty()) value = cached.contentDesc;
        if (value == null || value.isEmpty()) value = cached.text;
        if (value == null || value.isEmpty()) value = cached.label;
        return value;
    }

    /**
     * Check attribute from cached element
     */
    private static AttributeMatch checkAttributeCached(CachedElement cached, String attrName,
                                                       String targetValue, String strategy) {
        String attrValue = null;

        switch (attrName) {
            case "resource-id":
                attrValue = cached.resourceId;
                break;
            case "content-desc":
                attrValue = cached.contentDesc;
                break;
            case "text":
                attrValue = cached.text;
                break;
            case "name":
                attrValue = cached.name;
                break;
            case "label":
                attrValue = cached.label;
                break;
            case "value":
                attrValue = cached.value;
                break;
            case "type":
                attrValue = cached.type;
                break;
            case "class":
                attrValue = cached.className;
                break;
        }

        if (attrValue == null || attrValue.trim().isEmpty()) {
            return new AttributeMatch(strategy, "", 0.0);
        }

        double score = calculateSimilarity(targetValue, attrValue);
        return new AttributeMatch(strategy, attrValue, score);
    }

    /**
     * Get all attributes from cached element
     */
    private static String getAllAttributesCached(CachedElement cached, Platform platform) {
        StringBuilder sb = new StringBuilder();

        if (platform == Platform.IOS) {
            sb.append(cached.name).append(" ");
            sb.append(cached.label).append(" ");
            sb.append(cached.value).append(" ");
            sb.append(cached.type).append(" ");
        } else if (platform == Platform.ANDROID) {
            sb.append(cached.resourceId).append(" ");
            sb.append(cached.contentDesc).append(" ");
            sb.append(cached.text).append(" ");
            sb.append(cached.className).append(" ");
            sb.append(cached.contentDesc).append(" ");
        } else {
            sb.append(cached.name).append(" ");
            sb.append(cached.label).append(" ");
            sb.append(cached.text).append(" ");
            sb.append(cached.resourceId).append(" ");
        }

        return sb.toString();
    }

    // ------------------ HELPER METHODS ------------------

    /**
     * Detect platform from page source
     */
    private static Platform detectPlatform(String pageSource) {
        if (pageSource.contains("XCUIElement") || pageSource.contains("XCUIApplication")) {
            return Platform.IOS;
        } else if (pageSource.contains("android.") || pageSource.contains("com.android.")) {
            return Platform.ANDROID;
        } else {
            // Additional detection based on common attributes
            if (pageSource.contains("content-desc") || pageSource.contains("resource-id")) {
                return Platform.ANDROID;
            } else if (pageSource.contains("type=\"XCUIElementType")) {
                return Platform.IOS;
            }
            return Platform.UNKNOWN;
        }
    }

    /**
     * Strategy 1: Find by same locator strategy with similarity matching
     */
    private static HealedLocator findBySameStrategy(
            Document doc, String locatorType, String locatorValue, Platform platform) {

        NodeList allNodes = doc.getElementsByTagName("*");
        Map<Element, Double> candidates = new HashMap<>();

        String normalizedType = normalizeLocatorType(locatorType);

        for (int i = 0; i < allNodes.getLength(); i++) {
            Element el = (Element) allNodes.item(i);

            // Skip if element is not visible or not enabled
            if (!isElementUsable(el, platform)) {
                continue;
            }

            String attributeValue = extractAttributeForStrategy(el, normalizedType, platform);

            if (attributeValue != null && !attributeValue.trim().isEmpty()) {
                double score = calculateSimilarity(locatorValue, attributeValue);

                // Boost score for exact substring matches
                if (attributeValue.contains(locatorValue) || locatorValue.contains(attributeValue)) {
                    score = Math.min(1.0, score + 0.15);
                }

                if (score >= MINIMUM_SIMILARITY_THRESHOLD) {
                    candidates.put(el, score);
                }
            }
        }

        // Return best match
        return candidates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> createHealedLocator(entry.getKey(), normalizedType,
                        entry.getValue(), platform))
                .orElse(null);
    }

    /**
     * Strategy 2: Find by partial matching (useful for dynamic IDs)
     */
    private static HealedLocator findByPartialMatch(
            Document doc, String locatorType, String locatorValue, Platform platform) {

        NodeList allNodes = doc.getElementsByTagName("*");
        Map<Element, Double> candidates = new HashMap<>();

        // Extract key parts from locator value (remove numbers, dynamic IDs)
        String keyPart = extractKeyPart(locatorValue);
        String normalizedType = normalizeLocatorType(locatorType);

        if (keyPart.length() < 3) {
            return null; // Key part too short to match reliably
        }

        for (int i = 0; i < allNodes.getLength(); i++) {
            Element el = (Element) allNodes.item(i);

            if (!isElementUsable(el, platform)) {
                continue;
            }

            String attributeValue = extractAttributeForStrategy(el, normalizedType, platform);

            if (attributeValue != null && !attributeValue.trim().isEmpty()) {
                String candidateKeyPart = extractKeyPart(attributeValue);

                // Check if key part matches
                if (candidateKeyPart.toLowerCase().contains(keyPart.toLowerCase()) ||
                        keyPart.toLowerCase().contains(candidateKeyPart.toLowerCase())) {

                    double score = calculatePartialMatchScore(locatorValue, attributeValue);

                    if (score >= MINIMUM_SIMILARITY_THRESHOLD) {
                        candidates.put(el, score);
                    }
                }
            }
        }

        return candidates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> createHealedLocator(entry.getKey(), normalizedType,
                        entry.getValue(), platform))
                .orElse(null);
    }

    /**
     * Strategy 3: Find by alternative attributes (cross-check multiple attributes)
     */
    private static HealedLocator findByAlternativeAttributes(
            Document doc, String locatorValue, Platform platform) {

        NodeList allNodes = doc.getElementsByTagName("*");
        Map<Element, AttributeMatch> candidates = new HashMap<>();

        for (int i = 0; i < allNodes.getLength(); i++) {
            Element el = (Element) allNodes.item(i);

            if (!isElementUsable(el, platform)) {
                continue;
            }

            // Check all possible attributes based on platform
            List<AttributeMatch> matches = new ArrayList<>();

            if (platform == Platform.IOS) {
                matches.add(checkAttribute(el, "name", locatorValue, "accessibility"));
                matches.add(checkAttribute(el, "label", locatorValue, "name"));
                matches.add(checkAttribute(el, "value", locatorValue, "name"));
                matches.add(checkAttribute(el, "type", locatorValue, "classname"));
            } else if (platform == Platform.ANDROID) {
                matches.add(checkAttribute(el, "resource-id", locatorValue, "id"));
                matches.add(checkAttribute(el, "content-desc", locatorValue, "accessibility"));
                matches.add(checkAttribute(el, "text", locatorValue, "name"));
                matches.add(checkAttribute(el, "class", locatorValue, "classname"));
            }

            // Get best matching attribute
            AttributeMatch bestMatch = matches.stream()
                    .filter(m -> m.score >= MINIMUM_SIMILARITY_THRESHOLD)
                    .max(Comparator.comparingDouble(m -> m.score))
                    .orElse(null);

            if (bestMatch != null) {
                candidates.put(el, bestMatch);
            }
        }

        // Return best overall match
        return candidates.entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue().score))
                .map(entry -> {
                    AttributeMatch match = entry.getValue();
                    HealedLocator healed = new HealedLocator(
                            match.strategy,
                            match.attributeValue,
                            match.score,
                            platform
                    );
                    return healed;
                })
                .orElse(null);
    }

    /**
     * Strategy 4: Find by context (check parent, siblings for clues)
     */
    private static HealedLocator findByContext(
            Document doc, String locatorType, String locatorValue, Platform platform) {

        NodeList allNodes = doc.getElementsByTagName("*");
        Map<Element, Double> candidates = new HashMap<>();

        // Extract context keywords
        String[] keywords = locatorValue.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .split("\\s+");

        for (int i = 0; i < allNodes.getLength(); i++) {
            Element el = (Element) allNodes.item(i);

            if (!isElementUsable(el, platform)) {
                continue;
            }

            // Build context string (self + parent + siblings)
            StringBuilder context = new StringBuilder();

            // Self attributes
            context.append(getAllAttributes(el, platform)).append(" ");

            // Parent attributes
            Node parent = el.getParentNode();
            if (parent instanceof Element) {
                context.append(getAllAttributes((Element) parent, platform)).append(" ");
            }

            // Sibling attributes (previous and next)
            Node prevSibling = getPreviousElementSibling(el);
            if (prevSibling instanceof Element) {
                context.append(getAllAttributes((Element) prevSibling, platform)).append(" ");
            }

            Node nextSibling = getNextElementSibling(el);
            if (nextSibling instanceof Element) {
                context.append(getAllAttributes((Element) nextSibling, platform)).append(" ");
            }

            // Calculate keyword match score
            String contextLower = context.toString().toLowerCase();
            int matchCount = 0;
            for (String keyword : keywords) {
                if (keyword.length() > 2 && contextLower.contains(keyword)) {
                    matchCount++;
                }
            }

            if (keywords.length > 0) {
                double score = (double) matchCount / keywords.length;

                if (score >= MINIMUM_SIMILARITY_THRESHOLD) {
                    candidates.put(el, score);
                }
            }
        }

        return candidates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> createHealedLocator(entry.getKey(), "xpath",
                        entry.getValue(), platform))
                .orElse(null);
    }

    /**
     * Add alternative locator strategies for the found element
     */
    private static void addAlternativeLocators(HealedLocator primary, Document doc, Platform platform) {
        if (primary == null) return;

        // Find the element in the document using primary locator
        NodeList allNodes = doc.getElementsByTagName("*");
        Element targetElement = null;

        for (int i = 0; i < allNodes.getLength(); i++) {
            Element el = (Element) allNodes.item(i);
            String attrValue = extractAttributeForStrategy(el, primary.locatorStrategy, platform);

            if (primary.locatorValue.equals(attrValue)) {
                targetElement = el;
                break;
            }
        }

        if (targetElement == null) return;

        // Generate alternative locators
        List<String> alternatives = new ArrayList<>();

        if (platform == Platform.IOS) {
            // iOS alternatives
            String name = targetElement.getAttribute("name");
            String label = targetElement.getAttribute("label");
            String type = targetElement.getAttribute("type");

            if (name != null && !name.isEmpty() && !name.equals(primary.locatorValue)) {
                alternatives.add("AppiumBy.accessibilityId(\"" + name + "\")");
            }
            if (label != null && !label.isEmpty() && !label.equals(primary.locatorValue)) {
                alternatives.add("AppiumBy.name(\"" + label + "\")");
            }

            // iOS Class Chain
            if (name != null && !name.isEmpty() && type != null && !type.isEmpty()) {
                String classChain = "**/" + type + "[`name == '" + name + "'`]";
                alternatives.add("AppiumBy.iOSClassChain(\"" + classChain + "\")");
            }

            // iOS Predicate
            if (name != null && !name.isEmpty()) {
                String predicate = "name == '" + name + "'";
                alternatives.add("AppiumBy.iOSNsPredicateString(\"" + predicate + "\")");
            }

        } else if (platform == Platform.ANDROID) {
            // Android alternatives
            String resourceId = targetElement.getAttribute("resource-id");
            String contentDesc = targetElement.getAttribute("content-desc");
            String text = targetElement.getAttribute("text");

            if (resourceId != null && !resourceId.isEmpty() && !resourceId.equals(primary.locatorValue)) {
                alternatives.add("AppiumBy.id(\"" + resourceId + "\")");
            }
            if (contentDesc != null && !contentDesc.isEmpty() && !contentDesc.equals(primary.locatorValue)) {
                alternatives.add("AppiumBy.accessibilityId(\"" + contentDesc + "\")");
            }
            if (text != null && !text.isEmpty() && !text.equals(primary.locatorValue)) {
                alternatives.add("AppiumBy.name(\"" + text + "\")");
            }

            // UIAutomator
            if (resourceId != null && !resourceId.isEmpty()) {
                String uiAutomator = "new UiSelector().resourceId(\"" + resourceId + "\")";
                alternatives.add("AppiumBy.androidUIAutomator(\"" + uiAutomator + "\")");
            } else if (contentDesc != null && !contentDesc.isEmpty()) {
                String uiAutomator = "new UiSelector().description(\"" + contentDesc + "\")";
                alternatives.add("AppiumBy.androidUIAutomator(\"" + uiAutomator + "\")");
            }
        }

        // XPath (works for both)
        String xpath = buildXPath(targetElement, platform);
        if (xpath != null && !xpath.isEmpty()) {
            alternatives.add("AppiumBy.xpath(\"" + xpath + "\")");
        }

        primary.fallbackOptions = alternatives;
    }

    // ========== PLATFORM-AWARE HELPER METHODS ==========

    private static boolean isElementUsable(Element el, Platform platform) {
        // Check if element is visible and enabled
        String visible = el.getAttribute("visible");
        String enabled = el.getAttribute("enabled");

        // For Android
        if (platform == Platform.ANDROID) {
            String displayed = el.getAttribute("displayed");
            return !"false".equalsIgnoreCase(enabled) &&
                    !"false".equalsIgnoreCase(displayed);
        }

        // For iOS
        if (platform == Platform.IOS) {
            return !"false".equalsIgnoreCase(visible) &&
                    !"false".equalsIgnoreCase(enabled);
        }

        return true; // Unknown platform, assume usable
    }

    private static String normalizeLocatorType(String type) {
        if (type == null) return "accessibility";

        String lower = type.toLowerCase().replaceAll("[^a-z]", "");

        switch (lower) {
            case "id":
            case "resourceid":
                return "id";
            case "accessibility":
            case "aid":
            case "accessibilityid":
                return "accessibility";
            case "name":
            case "text":
                return "name";
            case "classname":
            case "cn":
            case "class":
                return "classname";
            case "xpath":
                return "xpath";
            case "uiautomator":
            case "androiduiautomator":
                return "uiautomator";
            case "classchain":
            case "iosclasschain":
                return "iOSClassChain";
            case "predicate":
            case "iospredicate":
            case "iosnspredicatestring":
                return "iOSPredicate";
            default:
                return "accessibility";
        }
    }

    /**
     * Extract search terms from locator key for cases where locator value is empty/blank
     * 
     * Examples:
     *   "login.input.email_address" → "email address"
     *   "btn_submit_form" → "submit form"
     *   "user.profile.name.field" → "name field"
     *   "LoginPage.emailInput" → "email input"
     * 
     * @param locatorKey The property key/name from page object
     * @return Extracted search terms
     */
    private static String extractSearchTermsFromKey(String locatorKey) {
        if (locatorKey == null || locatorKey.trim().isEmpty()) {
            return "";
        }
        
        // Common prefixes to remove (case-insensitive)
        String[] commonPrefixes = {
            "page", "element", "btn", "button", "input", "field", "lbl", "label", 
            "txt", "text", "img", "image", "link", "lnk", "div", "span", "section"
        };
        
        // Split by common separators: dots, underscores, hyphens, camelCase
        String processed = locatorKey
            .replaceAll("([a-z])([A-Z])", "$1 $2")  // camelCase to spaces
            .replaceAll("[._\\-]", " ")              // separators to spaces
            .toLowerCase()
            .trim();
        
        // Split into words
        String[] words = processed.split("\\s+");
        List<String> meaningfulWords = new ArrayList<>();
        
        // Filter out common prefixes and keep meaningful words
        Set<String> prefixSet = new HashSet<>(Arrays.asList(commonPrefixes));
        
        for (String word : words) {
            word = word.trim();
            // Keep word if it's not empty, not a prefix, and longer than 2 chars
            if (!word.isEmpty() && !prefixSet.contains(word) && word.length() > 2) {
                meaningfulWords.add(word);
            }
        }
        
        // If we filtered everything out, use last 2 words from original
        if (meaningfulWords.isEmpty() && words.length > 0) {
            int start = Math.max(0, words.length - 2);
            for (int i = start; i < words.length; i++) {
                if (!words[i].isEmpty()) {
                    meaningfulWords.add(words[i]);
                }
            }
        }
        
        String result = String.join(" ", meaningfulWords);
        
        // If still empty, use the last segment of the original key
        if (result.isEmpty()) {
            String[] segments = locatorKey.split("[._\\-]");
            result = segments[segments.length - 1];
        }
        
        return result;
    }

    private static String extractAttributeForStrategy(Element el, String strategy, Platform platform) {
        if (platform == Platform.IOS) {
            switch (strategy) {
                case "id":
                case "accessibility":
                    return el.getAttribute("name");
                case "name":
                    String label = el.getAttribute("label");
                    return (label != null && !label.isEmpty()) ? label : el.getAttribute("value");
                case "classname":
                    return el.getAttribute("type");
                case "text":
                    return el.getAttribute("value");
                default:
                    return el.getAttribute("name");
            }
        } else if (platform == Platform.ANDROID) {
            switch (strategy) {
                case "id":
                    return el.getAttribute("resource-id");
                case "accessibility":
                    return el.getAttribute("content-desc");
                case "name":
                case "text":
                    return el.getAttribute("text");
                case "classname":
                    return el.getAttribute("class");
                default:
                    return el.getAttribute("content-desc");
            }
        }

        // Unknown platform - try all common attributes
        String value = el.getAttribute("name");
        if (value == null || value.isEmpty()) value = el.getAttribute("content-desc");
        if (value == null || value.isEmpty()) value = el.getAttribute("text");
        if (value == null || value.isEmpty()) value = el.getAttribute("label");

        return value;
    }

    private static String extractKeyPart(String value) {
        // Remove common dynamic parts (numbers, hashes, timestamps, etc.)
        return value.replaceAll("\\d+", "")              // Remove numbers
                .replaceAll("[_-][a-f0-9]{8,}", "")  // Remove hash-like strings
                .replaceAll("\\s+", " ")             // Normalize spaces
                .replaceAll("[._-]+$", "")           // Remove trailing separators
                .replaceAll("^[._-]+", "")           // Remove leading separators
                .trim();
    }

    private static double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;

        // Levenshtein distance-based similarity
        int distance = levenshtein(s1.toLowerCase(), s2.toLowerCase());
        int maxLen = Math.max(s1.length(), s2.length());

        if (maxLen == 0) return 1.0;

        return 1.0 - ((double) distance / maxLen);
    }

    private static double calculatePartialMatchScore(String original, String candidate) {
        double baseSimilarity = calculateSimilarity(original, candidate);

        // Boost score if key parts match
        String originalKey = extractKeyPart(original);
        String candidateKey = extractKeyPart(candidate);

        if (originalKey.equalsIgnoreCase(candidateKey)) {
            baseSimilarity = Math.min(1.0, baseSimilarity + 0.2);
        }

        // Additional boost for contains relationship
        if (candidate.contains(original) || original.contains(candidate)) {
            baseSimilarity = Math.min(1.0, baseSimilarity + 0.1);
        }

        return baseSimilarity;
    }

    private static double similarity(String s1, String s2) {
        return calculateSimilarity(s1, s2);
    }

    private static int levenshtein(String lhs, String rhs) {
        int[][] dp = new int[lhs.length() + 1][rhs.length() + 1];
        for (int i = 0; i <= lhs.length(); i++) {
            for (int j = 0; j <= rhs.length(); j++) {
                if (i == 0) dp[i][j] = j;
                else if (j == 0) dp[i][j] = i;
                else dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + (lhs.charAt(i - 1) == rhs.charAt(j - 1) ? 0 : 1)
                );
            }
        }
        return dp[lhs.length()][rhs.length()];
    }

    private static AttributeMatch checkAttribute(Element el, String attrName,
                                                  String targetValue, String strategy) {
        String attrValue = el.getAttribute(attrName);
        if (attrValue == null || attrValue.trim().isEmpty()) {
            return new AttributeMatch(strategy, "", 0.0);
        }

        double score = calculateSimilarity(targetValue, attrValue);
        return new AttributeMatch(strategy, attrValue, score);
    }

    private static String getAllAttributes(Element el, Platform platform) {
        StringBuilder sb = new StringBuilder();

        if (platform == Platform.IOS) {
            sb.append(el.getAttribute("name")).append(" ");
            sb.append(el.getAttribute("label")).append(" ");
            sb.append(el.getAttribute("value")).append(" ");
            sb.append(el.getAttribute("type")).append(" ");
        } else if (platform == Platform.ANDROID) {
            sb.append(el.getAttribute("resource-id")).append(" ");
            sb.append(el.getAttribute("content-desc")).append(" ");
            sb.append(el.getAttribute("text")).append(" ");
            sb.append(el.getAttribute("class")).append(" ");
        } else {
            // Try all common attributes
            sb.append(el.getAttribute("name")).append(" ");
            sb.append(el.getAttribute("label")).append(" ");
            sb.append(el.getAttribute("text")).append(" ");
            sb.append(el.getAttribute("resource-id")).append(" ");
            sb.append(el.getAttribute("content-desc")).append(" ");
        }

        return sb.toString();
    }

    private static HealedLocator createHealedLocator(Element el, String strategy,
                                                     double score, Platform platform) {
        String value = extractAttributeForStrategy(el, strategy, platform);

        // If attribute is empty, fall back to XPath
        if (value == null || value.trim().isEmpty()) {
            strategy = "xpath";
            value = buildXPath(el, platform);
        }

        return new HealedLocator(strategy, value, score, platform);
    }

    private static String buildXPath(Element el, Platform platform) {
        if (el == null || el.getParentNode() == null) {
            return "/" + el.getNodeName();
        }

        String tag = el.getTagName();
        String text = el.getAttribute("text");
        String contentDesc = el.getAttribute("content-desc");
        String resourceId = el.getAttribute("resource-id");
        String name = el.getAttribute("name");
        String label = el.getAttribute("label");

        StringBuilder xpath = new StringBuilder("//").append(tag);

        if (platform == Platform.ANDROID) {
            if (!resourceId.isEmpty()) {
                xpath.append("[@resource-id='").append(resourceId).append("']");
            } else if (!contentDesc.isEmpty()) {
                xpath.append("[@content-desc='").append(contentDesc).append("']");
            } else if (!text.isEmpty()) {
                xpath.append("[@text='").append(text).append("']");
            }
        } else if (platform == Platform.IOS) {
            if (!name.isEmpty()) {
                xpath.append("[@name='").append(name).append("']");
            } else if (!label.isEmpty()) {
                xpath.append("[@label='").append(label).append("']");
            }
        } else {
            // Unknown platform - try common attributes
            if (!resourceId.isEmpty()) {
                xpath.append("[@resource-id='").append(resourceId).append("']");
            } else if (!name.isEmpty()) {
                xpath.append("[@name='").append(name).append("']");
            } else if (!contentDesc.isEmpty()) {
                xpath.append("[@content-desc='").append(contentDesc).append("']");
            } else if (!text.isEmpty()) {
                xpath.append("[@text='").append(text).append("']");
            }
        }

        return xpath.toString();
    }

    private static String buildXPath(Element el) {
        return buildXPath(el, Platform.UNKNOWN);
    }

    private static Node getPreviousElementSibling(Element el) {
        Node sibling = el.getPreviousSibling();
        while (sibling != null && sibling.getNodeType() != Node.ELEMENT_NODE) {
            sibling = sibling.getPreviousSibling();
        }
        return sibling;
    }

    private static Node getNextElementSibling(Element el) {
        Node sibling = el.getNextSibling();
        while (sibling != null && sibling.getNodeType() != Node.ELEMENT_NODE) {
            sibling = sibling.getNextSibling();
        }
        return sibling;
    }

    /**
     * Format healed locator for Appium usage
     */
    private static String formatLocatorForAppium(HealedLocator healed) {
        switch (healed.locatorStrategy) {
            case "id":
                return healed.platform == Platform.ANDROID
                        ? healed.locatorValue
                        : healed.locatorValue;
            case "accessibility":
                return healed.locatorValue;
            case "name":
                return healed.locatorValue;
            case "classname":
                return healed.locatorValue;
            case "xpath":
                return healed.locatorValue;
            case "uiautomator":
                return healed.locatorValue;
            case "iOSClassChain":
                return healed.locatorValue;
            case "iOSPredicate":
                return healed.locatorValue;
            default:
                return healed.locatorValue;
        }
    }

    // Helper class for attribute matching
    private static class AttributeMatch {
        String strategy;
        String attributeValue;
        double score;

        AttributeMatch(String strategy, String value, double score) {
            this.strategy = strategy;
            this.attributeValue = value;
            this.score = score;
        }
    }

    public static class VisualHealerService {

        private static final String API_KEY = System.getenv("OPENAI_API_KEY"); // AI key
        private static final String MODEL = "gpt-4o-mini"; // AI model

        /**
         * Attempts to locate element visually using OCR first, then AI fallback.
         *
         * @param screenshotPath Path to screenshot image
         * @param locator        Original By locator (for reference)
         * @return Rectangle of detected element, or null if not found
         */
        public static Rectangle findInScreenshot(String screenshotPath, By locator) {
            // 1️⃣ Try OCR-based text detection
            Rectangle ocrResult = findInScreenshotOCR(screenshotPath, locator);
            if (ocrResult != null) {
                System.out.println("Found element via OCR: " + locator);
                return ocrResult;
            }

            // 2️⃣ Fallback to AI model
            Rectangle aiResult = findInScreenshotAI(screenshotPath, locator);
            if (aiResult != null) {
                System.out.println("Found element via AI: " + locator);
            } else {
                System.err.println("Visual AI recovery failed for: " + locator);
            }

            return aiResult;
        }

        // ------------------ OCR LOCAL HEALING ------------------
        private static Rectangle findInScreenshotOCR(String screenshotPath, By locator) {
            try {
                File imageFile = new File(screenshotPath);
                if (!imageFile.exists()) return null;

                BufferedImage img = ImageIO.read(imageFile);

                ITesseract tesseract = new Tesseract();
                tesseract.setDatapath("/Users/vikasprasad/Documents/BI/BI_test_Automation/dms-app-test-automation/src/test/resources/TestData"); // Update to your tessdata path
                tesseract.setLanguage("eng");

                // Get words with bounding boxes
                List<Word> words = tesseract.getWords(img, ITesseract.RenderedFormat.TEXT.ordinal());

                // Extract locator text for matching
                String locatorText = locator.toString(); // or extract meaningful part from By
                for (Word word : words) {
                    String text = word.getText();
                    java.awt.Rectangle awtRect = word.getBoundingBox();
                    if (text != null && !text.isEmpty() && locatorText.contains(text)) {
                        return convertAwtToSelenium(awtRect);
                    }
                }

            } catch (Exception e) {
                System.err.println("Error during OCR visual healing: " + e.getMessage());
            }
            return null;
        }

        // ------------------ AI FALLBACK ------------------
        private static Rectangle findInScreenshotAI(String screenshotPath, By locator) {
            try {
                if (API_KEY == null || API_KEY.isEmpty()) return null;

                File screenshotFile = new File(screenshotPath);
                if (!screenshotFile.exists()) return null;

                byte[] fileBytes = Files.readAllBytes(screenshotFile.toPath());
                String base64Image = Base64.getEncoder().encodeToString(fileBytes);

                JSONObject body = new JSONObject();
                body.put("model", MODEL);
                body.put("input",
                        "You are an AI mobile automation assistant.\n" +
                                "Locate the element corresponding to: " + locator + "\n" +
                                "Screenshot is base64-encoded below:\n" + base64Image + "\n" +
                                "Return coordinates as x,y,width,height.");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.openai.com/v1/responses"))
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();

                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                JSONObject result = new JSONObject(response.body());
                String coordStr = result.optString("output", null); // expected: "x,y,width,height"
                if (coordStr != null && !coordStr.isEmpty()) {
                    String[] parts = coordStr.split(",");
                    if (parts.length == 4) {
                        int x = Integer.parseInt(parts[0].trim());
                        int y = Integer.parseInt(parts[1].trim());
                        int w = Integer.parseInt(parts[2].trim());
                        int h = Integer.parseInt(parts[3].trim());
                        return new Rectangle(x, y, w, h);
                    }
                }

            } catch (Exception e) {
                System.err.println("AI visual healing failed: " + e.getMessage());
            }

            return null;
        }

        public static Rectangle convertAwtToSelenium(java.awt.Rectangle awtRect) {
            if (awtRect == null) return null;
            return new Rectangle(awtRect.x, awtRect.y, awtRect.width, awtRect.height);
        }
    }

    public static class LocatorUtils {

        public static class LocatorInfo {
            public final String type;
            public final String value;

            public LocatorInfo(String type, String value) {
                this.type = type;
                this.value = value;
            }
        }

        /**
         * Extracts locator type and value from a By instance.
         * Works for AppiumBy, By.id, By.xpath, By.accessibilityId, etc.
         *
         * @param by the By locator
         * @return LocatorInfo with type and value
         */
        public static LocatorInfo extractLocatorTypeAndValue(By by) {
            if (by == null) return null;

            String byStr = by.toString(); // e.g., "By.id: login_button" or "AppiumBy.accessibilityId: login.emailinput"
            String type, value;

            // Handle both "By." and "AppiumBy." prefixes
            if (byStr.startsWith("By.id:") || byStr.startsWith("AppiumBy.id:")) {
                type = "id";
                value = byStr.replaceFirst("(Appium)?By\\.id: ", "").trim();
            } else if (byStr.startsWith("By.xpath:") || byStr.startsWith("AppiumBy.xpath:")) {
                type = "xpath";
                value = byStr.replaceFirst("(Appium)?By\\.xpath: ", "").trim();
            } else if (byStr.startsWith("By.className:") || byStr.startsWith("AppiumBy.className:")) {
                type = "classname";
                value = byStr.replaceFirst("(Appium)?By\\.className: ", "").trim();
            } else if (byStr.startsWith("By.name:") || byStr.startsWith("AppiumBy.name:")) {
                type = "name";
                value = byStr.replaceFirst("(Appium)?By\\.name: ", "").trim();
            } else if (byStr.startsWith("By.tagName:") || byStr.startsWith("AppiumBy.tagName:")) {
                type = "tag-name";
                value = byStr.replaceFirst("(Appium)?By\\.tagName: ", "").trim();
            } else if (byStr.startsWith("By.accessibilityId:") || byStr.startsWith("AppiumBy.accessibilityId:")) {
                type = "accessibility";
                value = byStr.replaceFirst("(Appium)?By\\.accessibilityId: ", "").trim();
            } else if (byStr.startsWith("By.iOSClassChain:") || byStr.startsWith("AppiumBy.iOSClassChain:")) {
                type = "iOSClassChain";
                value = byStr.replaceFirst("(Appium)?By\\.iOSClassChain: ", "").trim();
            } else if (byStr.startsWith("By.iOSNsPredicateString:") || byStr.startsWith("AppiumBy.iOSNsPredicateString:")) {
                type = "iOSPredicate";
                value = byStr.replaceFirst("(Appium)?By\\.iOSNsPredicateString: ", "").trim();
            } else if (byStr.startsWith("By.androidUIAutomator:") || byStr.startsWith("AppiumBy.androidUIAutomator:") || 
                       byStr.startsWith("By.ByAndroidUIAutomator:")) {
                type = "uiautomator";
                value = byStr.replaceFirst("(Appium)?By\\.(ByAndroid|android)UIAutomator: ", "").trim();
            } else if (byStr.startsWith("By.image:") || byStr.startsWith("AppiumBy.image:")) {
                type = "image";
                value = byStr.replaceFirst("(Appium)?By\\.image: ", "").trim();
            } else {
                // Fallback: try to extract type and value using regex
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(Appium)?By\\.(\\w+): (.+)");
                java.util.regex.Matcher matcher = pattern.matcher(byStr);
                if (matcher.matches()) {
                    String extractedType = matcher.group(2);
                    value = matcher.group(3).trim();
                    
                    // Normalize extracted type
                    switch (extractedType.toLowerCase()) {
                        case "accessibilityid":
                            type = "accessibility";
                            break;
                        case "iosclasschain":
                            type = "iOSClassChain";
                            break;
                        case "iosnspredicatestring":
                            type = "iOSPredicate";
                            break;
                        case "androiduiautomator":
                        case "byandroiduiautomator":
                            type = "uiautomator";
                            break;
                        default:
                            type = extractedType.toLowerCase();
                    }
                } else {
                    type = "unknown";
                    value = byStr;
                }
            }

            System.out.println("🔍 Extracted - Type: " + type + ", Value: " + value + " (from: " + byStr + ")");
            return new LocatorInfo(type, value);
        }
    }

   
}

