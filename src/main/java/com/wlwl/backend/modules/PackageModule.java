package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.wlwl.backend.db.PhpUniqid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Port of package/ — companies, listed straight from `company`.
 *
 * "package" here means a customer package (a company), not a Java package: the row is
 * keyed by packageId and carries companyName, agentId, the three run-model slots,
 * the availability window, commonPW, lang and companyType.
 *
 * Note PUT writes commonPW — the shared plaintext company password described in
 * PORTING_NOTES §9.1. Creating or editing a package is how that password is set; it is
 * pushed into user rows later by controldrill/put.php.
 */
@Component
public class PackageModule extends AbstractCatalogueModule {

    public PackageModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "package";
    }

    @Override
    protected String postSql() {
        return "select * from company";
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return List.of();
    }

    @Override
    protected void upsert(JsonNode qdata) {
        String packageId = text(qdata, "packageId");
        String companyName = text(qdata, "companyName");
        String agentId = text(qdata, "agentId");
        String runOne = text(qdata, "runOne");
        String runTwo = text(qdata, "runTwo");
        String runThree = text(qdata, "runThree");
        String availableFrom = text(qdata, "availableFrom");
        String availableTill = text(qdata, "availableTill");
        String commonPW = text(qdata, "commonPW");
        String lang = text(qdata, "lang");
        String companyType = text(qdata, "companyType");

        if (!truthy(packageId)) {
            jdbc.update("insert into company (packageId, companyName, agentId, runOne, "
                            + "runTwo, runThree, availableFrom, availableTill, commonPW, lang, "
                            + "companyType) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    PhpUniqid.next(), companyName, agentId, runOne, runTwo, runThree,
                    availableFrom, availableTill, commonPW, lang, companyType);
        } else {
            jdbc.update("update company set companyName = ?, agentId = ?, runOne = ?, "
                            + "runTwo = ?, runThree = ?, availableFrom = ?, availableTill = ?, "
                            + "commonPW = ?, lang = ?, companyType = ? where packageId = ?",
                    companyName, agentId, runOne, runTwo, runThree, availableFrom,
                    availableTill, commonPW, lang, companyType, packageId);
        }
    }

    @Override
    protected String putSelectSql() {
        return "select * from company where packageId = ?";
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return List.of(text(qdata, "packageId"));
    }
}
