package com.devicehive.model;

import com.devicehive.json.strategies.JsonPolicyDef;
import com.google.gson.annotations.SerializedName;
import org.hibernate.annotations.CacheConcurrencyStrategy;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import static com.devicehive.json.strategies.JsonPolicyDef.Policy.IDENTITY_PROVIDER_LISTED;

/**
 * Created by tmatvienko on 11/17/14.
 */
@Entity
@Table(name = "identity_provider")
@NamedQueries({
        @NamedQuery(name = "IdentityProvider.getByName", query = "select ip from IdentityProvider ip where ip.name = :name"),
        @NamedQuery(name = "IdentityProvider.deleteByName", query = "delete from IdentityProvider ip where ip.name = :name")
})
@Cacheable
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class IdentityProvider implements HiveEntity {

    private static final long serialVersionUID = 1959997436981843212L;

    @Id
    @NotNull(message = "name field cannot be null.")
    @Size(min = 1, max = 64, message = "Field cannot be empty. The length of login should not be more than 64 " +
            "symbols.")
    @SerializedName("name")
    @JsonPolicyDef({IDENTITY_PROVIDER_LISTED})
    private String name;

    @Column(name = "api_endpoint")
    @NotNull(message = "identity provider's api endpoint can't be null.")
    @SerializedName("apiEndpoint")
    @JsonPolicyDef({IDENTITY_PROVIDER_LISTED})
    private String apiEndpoint;

    @Column(name = "verification_endpoint")
    @NotNull(message = "identity provider's verification endpoint can't be null.")
    @SerializedName("verificationEndpoint")
    @JsonPolicyDef({IDENTITY_PROVIDER_LISTED})
    private String verificationEndpoint;

    @Column(name = "token_endpoint")
    @NotNull(message = "identity provider's access token endpoint can't be null.")
    @SerializedName("tokenEndpoint")
    @JsonPolicyDef({IDENTITY_PROVIDER_LISTED})
    private String tokenEndpoint;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getVerificationEndpoint() {
        return verificationEndpoint;
    }

    public void setVerificationEndpoint(String verificationEndpoint) {
        this.verificationEndpoint = verificationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        IdentityProvider user = (IdentityProvider) o;

        return name != null && name.equals(user.name);
    }

    @Override
    public int hashCode() {
        return name == null ? 0 : name.hashCode();
    }

}
