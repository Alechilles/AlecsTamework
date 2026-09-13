package com.alechilles.alecstamework.ui;

/** Topic order and localization identities; all guide copy lives in server.lang. */
final class TameworkCompanionGuideContent {
    private TameworkCompanionGuideContent() { }

    static final Topic[] TOPICS = {
            new Topic("gettingStarted"),
            new Topic("readingCard"),
            new Topic("commandsGroups"),
            new Topic("careHappiness"),
            new Topic("breedingGrowth"),
            new Topic("traitsTalents"),
            new Topic("captureCoops"),
            new Topic("findingRecovery"),
            new Topic("bondedCompanions"),
            new Topic("travelFlight"),
            new Topic("worldUtilities")
    };

    record Topic(String key) { }
}
