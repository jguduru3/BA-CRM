package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.entity.Contact;
import com.betteranalytics.crm.entity.Role;
import com.betteranalytics.crm.entity.User;
import com.betteranalytics.crm.repository.ContactRepository;
import com.betteranalytics.crm.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContactCategoryControllerTest extends IntegrationTest {

    @Autowired ContactRepository contactRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void permissionsAndTenantIsolation() throws Exception {
        Long org = newOrg();
        User manager = user(org, Role.MANAGER);
        User rep = user(org, Role.EXECUTIVE);
        User support = user(org, Role.SUPPORT);
        User stranger = user(newOrg(), Role.SUPER_ADMIN);
        long id = idOf(postAs(manager, "/api/contact-categories", Map.of("name", "Pharma")).andExpect(status().isCreated()));
        getAs(rep, "/api/contact-categories").andExpect(status().isOk());
        getAs(support, "/api/contact-categories").andExpect(status().isOk());
        postAs(rep, "/api/contact-categories", Map.of("name", "x")).andExpect(status().isForbidden());
        postAs(support, "/api/contact-categories", Map.of("name", "y")).andExpect(status().isForbidden());
        putAs(support, "/api/contact-categories/" + id, Map.of("name", "y")).andExpect(status().isForbidden());
        deleteAs(support, "/api/contact-categories/" + id).andExpect(status().isForbidden());
        putAs(stranger, "/api/contact-categories/" + id, Map.of("name", "y")).andExpect(status().isNotFound());
        deleteAs(stranger, "/api/contact-categories/" + id).andExpect(status().isNotFound());
        JsonNode theirs = body(getAs(stranger, "/api/contact-categories"));
        theirs.forEach(c -> assertThat(c.path("id").asLong()).isNotEqualTo(id));
    }

    @Test
    void partialUpdateKeepsUntouchedFields() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        long id = idOf(postAs(manager, "/api/contact-categories",
                Map.of("name", "Pharma", "color", "#123456", "industry", "Health", "country", "IN")));
        JsonNode c = body(putAs(manager, "/api/contact-categories/" + id, Map.of("description", "d")).andExpect(status().isOk()));
        assertThat(c.path("color").asText()).isEqualTo("#123456");
        assertThat(c.path("industry").asText()).isEqualTo("Health");
        assertThat(c.path("country").asText()).isEqualTo("IN");
        assertThat(c.path("active").asBoolean()).isTrue();
    }

    @Test
    void deletingACategoryInUseUncategorisesContacts() throws Exception {
        User manager = user(newOrg(), Role.MANAGER);
        long id = idOf(postAs(manager, "/api/contact-categories", Map.of("name", "Pharma")));
        Contact contact = new Contact();
        contact.setOrganizationId(manager.getOrganizationId());
        contact.setName("Jane");
        contact.setOwnerId(manager.getId());
        contact.setCreatedAt(Instant.now());
        contact.setUpdatedAt(Instant.now());
        contact = contactRepository.save(contact);
        jdbc.update("UPDATE contacts SET category_id = ? WHERE id = ?", id, contact.getId());

        deleteAs(manager, "/api/contact-categories/" + id).andExpect(status().isOk());
        Long categoryId = jdbc.queryForObject("SELECT category_id FROM contacts WHERE id = ?", Long.class, contact.getId());
        assertThat(categoryId).isNull();
    }
}
