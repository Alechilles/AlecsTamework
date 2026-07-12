package com.alechilles.alecstamework.build;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the private SLF4J facade and binding used by the shaded SQLite runtime.
 */
class Slf4jShadeIsolationTest {
    private static final Path POM = Path.of("pom.xml");
    private static final String PRIVATE_SLF4J =
            "com.alechilles.alecstamework.shadow.slf4j";
    private static final String PROJECT = "/*[local-name()='project']";

    /** Protects the packaged-runtime regression that previously emitted a missing-binder warning. */
    @Test
    void apiAndNopBindingUseTheSamePinnedSlf4jVersion() throws Exception {
        Document pom = readPom();

        assertEquals(
                "1.7.36",
                text(pom, PROJECT + "/*[local-name()='properties']"
                        + "/*[local-name()='slf4j.version']")
        );
        assertSingleDependencyUsingVersionProperty(pom, "slf4j-api");
        assertSingleDependencyUsingVersionProperty(pom, "slf4j-nop");
    }

    @Test
    void shadePackagesApiAndNopBindingInsideThePrivateNamespace() throws Exception {
        Document pom = readPom();
        String shade = PROJECT + "/*[local-name()='build']/*[local-name()='plugins']"
                + "/*[local-name()='plugin'][*[local-name()='artifactId']='maven-shade-plugin']"
                + "/*[local-name()='executions']/*[local-name()='execution']"
                + "/*[local-name()='configuration']";

        assertEquals(
                1,
                count(pom, shade + "/*[local-name()='artifactSet']/*[local-name()='includes']"
                        + "/*[local-name()='include'][text()='org.slf4j:slf4j-api']"),
                "The shaded jar must contain the SLF4J facade used by SQLite."
        );
        assertEquals(
                1,
                count(pom, shade + "/*[local-name()='artifactSet']/*[local-name()='includes']"
                        + "/*[local-name()='include'][text()='org.slf4j:slf4j-nop']"),
                "The shaded jar must contain the matching no-op StaticLoggerBinder."
        );
        assertEquals(
                1,
                count(
                        pom,
                        shade + "/*[local-name()='relocations']/*[local-name()='relocation']"
                                + "[*[local-name()='pattern']='org.slf4j' and "
                                + "*[local-name()='shadedPattern']='" + PRIVATE_SLF4J + "']"
                ),
                "SLF4J API and binding classes must remain isolated from other Hytale mods."
        );
    }

    @Test
    void sourceDoesNotPublishAnUnrelocatedSlf4jServiceProvider() throws Exception {
        String pom = Files.readString(POM);

        assertFalse(
                pom.contains("META-INF/services/org.slf4j"),
                "SLF4J 1.7 NOP uses the relocated StaticLoggerBinder, not a global service provider."
        );
        assertFalse(
                pom.contains("org.slf4j:slf4j-simple"),
                "Tamework must not expose a logging provider that can collide with other mods."
        );
    }

    private static void assertSingleDependencyUsingVersionProperty(
            Document pom,
            String artifactId
    ) throws Exception {
        String dependency = PROJECT + "/*[local-name()='dependencies']"
                + "/*[local-name()='dependency'][*[local-name()='groupId']='org.slf4j'"
                + " and *[local-name()='artifactId']='" + artifactId + "']";
        assertEquals(1, count(pom, dependency), artifactId + " must be declared exactly once.");
        assertEquals(
                "${slf4j.version}",
                text(pom, dependency + "/*[local-name()='version']"),
                artifactId + " must use the shared pinned SLF4J version."
        );
    }

    private static Document readPom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(POM.toFile());
    }

    private static int count(Document pom, String expression) throws Exception {
        XPath xpath = XPathFactory.newInstance().newXPath();
        return ((Double) xpath.evaluate(
                "count(" + expression + ")",
                pom,
                XPathConstants.NUMBER
        )).intValue();
    }

    private static String text(Document pom, String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, pom).trim();
    }
}
