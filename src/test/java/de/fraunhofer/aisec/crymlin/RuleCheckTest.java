
package de.fraunhofer.aisec.crymlin;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import de.fraunhofer.aisec.cpg.TranslationConfiguration;
import de.fraunhofer.aisec.cpg.TranslationManager;
import de.fraunhofer.aisec.cpg.TranslationResult;
import de.fraunhofer.aisec.crymlin.connectors.db.Database;
import de.fraunhofer.aisec.crymlin.connectors.db.OverflowDatabase;
import de.fraunhofer.aisec.crymlin.server.AnalysisContext;
import de.fraunhofer.aisec.crymlin.server.AnalysisServer;
import de.fraunhofer.aisec.crymlin.server.ServerConfiguration;
import de.fraunhofer.aisec.crymlin.structures.Finding;
import java.io.File;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class RuleCheckTest {

	private static AnalysisServer server;
	private static TranslationResult result;

	@BeforeAll
	public static void startup() throws Exception {

		ClassLoader classLoader = AnalysisServerBotanTest.class.getClassLoader();

		URL resource = classLoader.getResource("mark_rule_eval.cpp");
		assertNotNull(resource);
		File cppFile = new File(resource.getFile());
		assertNotNull(cppFile);

		resource = classLoader.getResource("mark_rule_eval.mark");
		assertNotNull(resource);
		File markPoC1 = new File(resource.getFile());
		assertNotNull(markPoC1);

		// Make sure we start with a clean (and connected) db
		try {
			Database db = OverflowDatabase.getInstance();
			db.connect();
			db.purgeDatabase();
		}
		catch (Throwable e) {
			e.printStackTrace();
			assumeFalse(true); // Assumption for this test not fulfilled. Do not fail but bail.
		}

		// Start an analysis server
		server = AnalysisServer.builder().config(
			ServerConfiguration.builder().launchConsole(false).launchLsp(false).markFiles(markPoC1.getAbsolutePath()).build()).build();
		server.start();

		TranslationManager translationManager = TranslationManager.builder().config(
			TranslationConfiguration.builder().debugParser(true).failOnError(false).codeInNodes(true).defaultPasses().sourceFiles(cppFile).build()).build();
		CompletableFuture<TranslationResult> analyze = server.analyze(translationManager);
		try {
			result = analyze.get(5, TimeUnit.MINUTES);
		}
		catch (TimeoutException t) {
			analyze.cancel(true);
			throw t;
		}
	}

	@AfterAll
	public static void teardown() throws Exception {
		// Stop the analysis server
		server.stop();
	}

	@Test
	public void sanityTest() {
		AnalysisContext ctx = (AnalysisContext) result.getScratch().get("ctx");
		assertNotNull(ctx);
		assertTrue(ctx.methods.isEmpty());
	}

	@Test
	public void ruleCheckTest() throws Exception {
		AnalysisContext ctx = (AnalysisContext) result.getScratch().get("ctx");
		assertNotNull(ctx.getFindings());
		Set<Finding> findings = ctx.getFindings();
		int markRuleEvaluationFindingCount = 0;
		int satisfied = 0;
		int violated = 0;
		int unknown = 0;
		int guardingUnsatisfied = 0;
		int guardingUnknown = 0;
		for (Finding f : findings) {
			if (f.getName().contains("MarkRuleEvaluationFinding")) {
				markRuleEvaluationFindingCount++;
				if (f.getName().contains("guarding condition unsatisfied")) {
					guardingUnsatisfied++;

				}
				else if (f.getName().contains("guarding condition unknown")) {
					guardingUnknown++;

				}
				else if (f.getName().contains("ensure condition unknown")) {
					unknown++;

				}
				else if (f.getName().contains("ensure condition violated")) {
					violated++;

				}
				else if (f.getName().contains("ensure condition satisfied")) {
					satisfied++;
				}
			}
		}
		assertEquals(3, markRuleEvaluationFindingCount);
		assertEquals(2, satisfied);
		assertEquals(0, violated);
		assertEquals(0, unknown);
		assertEquals(0, guardingUnsatisfied);
		assertEquals(1, guardingUnknown);
	}
}
