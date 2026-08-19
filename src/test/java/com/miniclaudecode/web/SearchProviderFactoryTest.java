package com.miniclaudecode.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchProviderFactoryTest {

    @Test
    void explicitProviderWinsForAllSupportedNames() {
        assertEquals("anysearch", SearchProviderFactory.pickProvider("anysearch"));
        assertEquals("zhipu", SearchProviderFactory.pickProvider("ZHIPU"));
        assertEquals("searxng", SearchProviderFactory.pickProvider("searxng"));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("serpapi"));
    }

    @Test
    void blankExplicitFallsBackToAnySearch() {
        assertEquals("anysearch", SearchProviderFactory.pickProvider(null));
        assertEquals("anysearch", SearchProviderFactory.pickProvider(""));
        assertEquals("anysearch", SearchProviderFactory.pickProvider("   "));
    }

    @Test
    void createDefaultsToAnySearchImplementation() {
        // 无任何环境干预时拿到 AnySearchProvider，且匿名档即 ready
        SearchProvider provider = SearchProviderFactory.create();
        assertEquals("anysearch", provider.name());
        org.junit.jupiter.api.Assertions.assertTrue(provider.isReady());
    }
}
