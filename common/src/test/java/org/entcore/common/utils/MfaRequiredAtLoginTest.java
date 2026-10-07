package org.entcore.common.utils;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Second facteur exigé dès la connexion : jamais sans moyen de recevoir ou produire un code. */
public class MfaRequiredAtLoginTest {

    @BeforeClass
    public static void setUp() {
        // Plateforme : code par e-mail ou application d'authentification
        Mfa.Factory.getFactory().init(null, new JsonObject().put("mfaConfig",
                new JsonObject().put("types", new JsonArray().add("email").add("totp"))));
    }

    @Test
    public void notRequiredWithoutFlag() {
        assertFalse(Mfa.isRequiredAtLogin(new JsonObject().put("email", "a@b.fr")));
        assertFalse(Mfa.isRequiredAtLogin(null));
    }

    @Test
    public void requiredWithEmail() {
        assertTrue(Mfa.isRequiredAtLogin(new JsonObject().put("mfaAtLogin", true).put("email", "a@b.fr")));
    }

    @Test
    public void requiredWithEnrolledApp() {
        assertTrue(Mfa.isRequiredAtLogin(new JsonObject().put("mfaAtLogin", true).put("hasTotp", true)));
    }

    @Test
    public void notRequiredWithoutAnyMethod() {
        // Élève sans adresse e-mail ni application : l'exiger l'enfermerait dehors.
        assertFalse(Mfa.isRequiredAtLogin(new JsonObject().put("mfaAtLogin", true).put("email", " ")));
        // SMS non configuré sur la plateforme : un mobile ne suffit pas.
        assertFalse(Mfa.isRequiredAtLogin(new JsonObject().put("mfaAtLogin", true).put("mobile", "+33612345678")));
    }
}
