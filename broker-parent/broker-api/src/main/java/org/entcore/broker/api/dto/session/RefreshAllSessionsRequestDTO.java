package org.entcore.broker.api.dto.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.beans.Transient;

/**
 * DTO for the request to refresh every active session of a given user.
 */
public class RefreshAllSessionsRequestDTO {
    private final String userId;

    /**
     * Constructor for RefreshAllSessionsRequestDTO.
     * @param userId The user whose sessions should be refreshed.
     */
    @JsonCreator
    public RefreshAllSessionsRequestDTO(@JsonProperty("userId") String userId) {
        this.userId = userId;
    }

    /**
     * @return The user id.
     */
    public String getUserId() { return userId; }

    /**
     * Checks if the request is valid (userId must not be empty).
     * @return true if valid, false otherwise.
     */
    @Transient
    public boolean isValid() {
        return userId != null && !userId.isEmpty();
    }

    @Override
    public String toString() {
        return "RefreshAllSessionsRequestDTO{" +
                "userId='" + (userId != null ? userId : "null") + '\'' +
                '}';
    }
}
