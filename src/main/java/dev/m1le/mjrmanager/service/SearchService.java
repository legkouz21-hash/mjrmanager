package dev.m1le.mjrmanager.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class SearchService {

    public static class SearchResult {
        public String className;
        public String filePath;
        public int lineNumber;
        public String lineContent;
        public String context;

        public SearchResult(String className, String filePath, int lineNumber, String lineContent, String context) {
            this.className = className;
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.lineContent = lineContent;
            this.context = context;
        }

        @Override
        public String toString() {
            return className + " (строка " + lineNumber + "): " + lineContent.trim();
        }
    }

    private final DecompilerService decompilerService;

    public SearchService(DecompilerService decompilerService) {
        this.decompilerService = decompilerService;
    }


    public List<SearchResult> searchInContent(String query, Map<String, byte[]> jarEntries, 
                                               boolean caseSensitive, boolean useRegex) {
        List<SearchResult> results = Collections.synchronizedList(new ArrayList<>());
        
        if (query == null || query.trim().isEmpty()) {
            return results;
        }

        Pattern pattern = null;
        if (useRegex) {
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                pattern = Pattern.compile(query, flags);
            } catch (PatternSyntaxException e) {
                return results;
            }
        }

        final Pattern finalPattern = pattern;
        final String searchQuery = caseSensitive ? query : query.toLowerCase();

        ExecutorService executor = Executors.newFixedThreadPool(
            Math.min(Runtime.getRuntime().availableProcessors(), 8)
        );

        List<Future<?>> futures = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : jarEntries.entrySet()) {
            String entryPath = entry.getKey();
            
            if (!entryPath.endsWith(".class")) {
                continue;
            }

            Future<?> future = executor.submit(() -> {
                try {
                    String decompiled = decompilerService.decompile(
                        entryPath, entry.getValue(), jarEntries
                    );

                    String[] lines = decompiled.split("\n");
                    String className = extractClassName(entryPath);

                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        boolean matches = false;

                        if (useRegex && finalPattern != null) {
                            matches = finalPattern.matcher(line).find();
                        } else {
                            String searchLine = caseSensitive ? line : line.toLowerCase();
                            matches = searchLine.contains(searchQuery);
                        }

                        if (matches) {
                            String context = getContext(lines, i, 2);
                            results.add(new SearchResult(
                                className, 
                                entryPath, 
                                i + 1, 
                                line, 
                                context
                            ));
                        }
                    }
                } catch (Exception e) {
                }
            });

            futures.add(future);
        }

        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                future.cancel(true);
            }
        }

        executor.shutdown();

        results.sort(Comparator.comparing((SearchResult r) -> r.className)
                              .thenComparing(r -> r.lineNumber));

        return results;
    }

    private String extractClassName(String entryPath) {
        String name = entryPath.replace(".class", "");
        int lastSlash = name.lastIndexOf('/');
        return lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
    }

    private String getContext(String[] lines, int lineIndex, int contextLines) {
        StringBuilder context = new StringBuilder();
        
        int start = Math.max(0, lineIndex - contextLines);
        int end = Math.min(lines.length - 1, lineIndex + contextLines);

        for (int i = start; i <= end; i++) {
            if (i == lineIndex) {
                context.append(">>> ");
            } else {
                context.append("    ");
            }
            context.append(lines[i]).append("\n");
        }

        return context.toString();
    }
}
