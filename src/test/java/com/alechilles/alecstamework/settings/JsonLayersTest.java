package com.alechilles.alecstamework.settings;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonLayersTest {

    @Test
    void overridesMergedBackOverTheBaseGiveTheFullDocument() {
        JsonObject base = json("{'a':1,'onlyInBase':true,'group':{'x':'keep','y':2},'words':['one','two']}");
        JsonObject full = json("{'a':1,'group':{'x':'keep','y':3},'words':['one'],'onlyInFull':'new'}");

        JsonObject overrides = JsonLayers.overrides(full, base);

        assertEquals(full, JsonLayers.merge(base, overrides));
        // Only what differs is kept: y, the whole list, the new key and the unset of onlyInBase.
        assertEquals(4, JsonLayers.count(overrides));
    }

    @Test
    void aJoiningServerKeepsOnlyValuesThatDifferFromTheNetworkAndTheDefault() {
        JsonObject defaults = json("{'limit':0,'group':{'enabled':true,'rate':2.0},'words':[]}");
        JsonObject network = json("{'limit':5,'group':{'enabled':false,'rate':2.0},'words':['net']}");
        // limit was chosen, group.enabled is the untouched default, group.rate equals both, words was chosen.
        JsonObject local = json("{'limit':8,'group':{'enabled':true,'rate':2.0},'words':['mine']}");

        JsonObject kept = JsonLayers.chosenOverrides(local, network, defaults);

        assertEquals(json("{'limit':8,'words':['mine']}"), kept);
        assertEquals(json("{'limit':8,'group':{'enabled':false,'rate':2.0},'words':['mine']}"),
                JsonLayers.merge(network, kept));
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text.replace('\'', '"')).getAsJsonObject();
    }
}
