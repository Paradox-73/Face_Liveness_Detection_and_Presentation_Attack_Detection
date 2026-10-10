/*
 * Copyright (c) 2026 Team Badmosip
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/** Queries on the running client's own database (the Spring data source of the client JVM). */
final class FlowDb {

	static final int NO_ROWS = -2;

	private final FlowContext c;

	FlowDb(FlowContext c) {
		this.c = c;
	}

	/** Runs a query and logs the rows; with {@code minRows >= 0} or {@link #NO_ROWS} it also records a RESULT. */
	void sql(String query, int minRows) throws Exception {
		int rows = 0;
		try {
			Object context = Class.forName("io.mosip.registration.controller.ClientApplication")
					.getMethod("getApplicationContext").invoke(null);
			DataSource ds = (DataSource) context.getClass().getMethod("getBean", Class.class).invoke(context,
					DataSource.class);
			try (Connection con = ds.getConnection(); Statement s = con.createStatement();
					ResultSet rs = s.executeQuery(query)) {
				ResultSetMetaData md = rs.getMetaData();
				while (rs.next()) {
					List<String> cells = new ArrayList<>();
					for (int i = 1; i <= md.getColumnCount(); i++) {
						cells.add(md.getColumnLabel(i) + "=" + rs.getString(i));
					}
					c.logLine("  ROW " + String.join(" | ", cells));
					rows++;
				}
			}
		} catch (Exception e) {
			c.logLine("  SQL error " + e);
		}
		c.logLine("  rows=" + rows);
		if (minRows == NO_ROWS) {
			c.result(rows == 0, "sql no rows: " + query);
		} else if (minRows >= 0) {
			c.result(rows >= minRows, "sql rows>=" + minRows + ": " + query);
		}
	}
}
