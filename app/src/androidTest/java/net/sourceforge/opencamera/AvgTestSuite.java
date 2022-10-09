package net.sourceforge.opencamera;

import org.junit.experimental.categories.Categories;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Categories.class)
@Categories.IncludeCategory(AvgTests.class)
@Suite.SuiteClasses({InstrumentedTest.class})
public class AvgTestSuite {}
