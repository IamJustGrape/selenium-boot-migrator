class BaseTest {
    WebDriver driver;

    @BeforeMethod
    void setUp() {
        driver = new ChromeDriver();
    }

    @AfterMethod
    void tearDown() {
        driver.quit();
    }

    @BeforeClass
    void setUpClass() {
        driver = new FirefoxDriver();
    }
}