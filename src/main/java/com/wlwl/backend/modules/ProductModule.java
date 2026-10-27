package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Port of product/ — products are baseinfo rows (baseclass 'products') left-joined to
 * the `product` table, with `productShortName <> ''` excluding un-configured rows.
 *
 * Two oddities:
 *  - the INSERT hardcodes the productName to the literal 'basic-product' (the shortest
 *    name wins because productName is the primary key);
 *  - the branch tests `$qdata->productName`, but the UPDATE keys on
 *    `productShortName = $qdata->productName`.
 */
@Component
public class ProductModule extends AbstractCatalogueModule {

    public ProductModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "product";
    }

    @Override
    protected String postSql() {
        return "select * from baseinfo b left join product p on b.name = p.productName "
                + "where b.baseclass = 'products' and productShortName <> ''";
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return List.of();
    }

    @Override
    protected void upsert(JsonNode qdata) {
        String description = text(qdata, "productDescription");
        int ordersPerDay = intOf(qdata, "ordersPerDay");
        double minValue = doubleOf(qdata, "minOrderValue");
        double maxValue = doubleOf(qdata, "maxOrderValue");
        String shortName = text(qdata, "productShortName");

        if (!truthy(text(qdata, "productName"))) {
            // Literal 'basic-product', exactly as the PHP writes it.
            jdbc.update("insert into product (productName, productDescription, ordersPerDay, "
                            + "minOrderValue, maxOrderValue, productShortName) "
                            + "values ('basic-product', ?, ?, ?, ?, ?)",
                    description, ordersPerDay, minValue, maxValue, shortName);
        } else {
            jdbc.update("update product set productDescription = ?, ordersPerDay = ?, "
                            + "minOrderValue = ?, maxOrderValue = ?, productShortName = ? "
                            + "where productShortName = ?",
                    description, ordersPerDay, minValue, maxValue, shortName,
                    text(qdata, "productName"));
        }
    }

    @Override
    protected String putSelectSql() {
        return "select * from baseinfo b left join product p on b.name = p.productName "
                + "where b.baseclass = 'products' and productShortName = ?";
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return List.of(text(qdata, "productShortName"));
    }
}
