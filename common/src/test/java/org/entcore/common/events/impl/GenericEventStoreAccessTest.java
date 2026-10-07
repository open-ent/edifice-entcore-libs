package org.entcore.common.events.impl;

import org.entcore.common.events.EventHelper;
import org.entcore.common.user.UserInfos;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GenericEventStoreAccessTest {

  @Test
  public void anonymousAccessIsNeverADuplicate() {
    // Avant correctif : NullPointerException, et la vitrine publique du portail répondait 500.
    assertFalse(GenericEventStore.isDuplicateAccessModule(null, null, "Portal", EventHelper.ACCESS_EVENT));
  }

  @Test
  public void sameModuleAsLastAccessIsADuplicate() {
    final UserInfos user = new UserInfos();
    final Map<String, Object> session = new HashMap<>();
    session.put("lastAccessModule", "Portal");
    user.setCache(session);
    assertTrue(GenericEventStore.isDuplicateAccessModule(null, user, "Portal", EventHelper.ACCESS_EVENT));
  }

  @Test
  public void otherEventTypesAreNeverDuplicates() {
    assertFalse(GenericEventStore.isDuplicateAccessModule(null, null, "Portal", "CREATE"));
  }
}
