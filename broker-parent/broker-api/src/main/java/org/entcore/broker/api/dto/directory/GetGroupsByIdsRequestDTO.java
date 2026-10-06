package org.entcore.broker.api.dto.directory;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.beans.Transient;

/**
 * DTO for requesting group information by their ENT IDs.
 */
public class GetGroupsByIdsRequestDTO {
    private final List<String> groupIds;

    @JsonCreator
    public GetGroupsByIdsRequestDTO(@JsonProperty("groupIds") List<String> groupIds) {
        this.groupIds = groupIds;
    }

    public List<String> getGroupIds() {
        return groupIds;
    }

    /**
     * Validates that the request contains the necessary data.
     * @return true if the request is valid, false otherwise
     */
    @Transient()
    public boolean isValid() {
        return groupIds != null && !groupIds.isEmpty();
    }

    @Override
    public String toString() {
        return "GetGroupsByIdsRequestDTO{" +
                "groupIds=" + groupIds +
                '}';
    }
}
