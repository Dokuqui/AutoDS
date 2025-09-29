import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.By
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import jakarta.mail.*
import jakarta.mail.internet.*
import java.util.Properties

def EMAIL_USER = System.getenv('EMAIL_USER')
def EMAIL_PASS = System.getenv('EMAIL_PASS')
def TELEGRAM_BOT_TOKEN = System.getenv('TELEGRAM_BOT_TOKEN')
def TELEGRAM_CHAT_ID = System.getenv('TELEGRAM_CHAT_ID')

def SEEN_FILE = "leboncoin_seen.txt"
def LEBONCOIN_URL = "https://www.leboncoin.fr/recherche?category=10&locations=Bordeaux__44.85027430275702_-0.5749636855863279_9036_5000&price=min-730&real_estate_type=2&owner_type=private"

// System.setProperty("webdriver.chrome.driver", "C:\\chromedriver\\chromedriver.exe")

def options = new ChromeOptions()
options.addArguments("--headless=new")
options.addArguments("--disable-gpu")
options.addArguments("--window-size=1920,1080")
options.addArguments("--lang=fr")

def driver = new ChromeDriver(options)

driver.get(LEBONCOIN_URL)
Thread.sleep(5000)

def ads = driver.findElements(By.cssSelector("a.styles_adCard__2YFTi"))

def seenUrls = new File(SEEN_FILE).exists() ? new File(SEEN_FILE).readLines().toSet() : [] as Set

def newListings = []

ads.each { ad ->
    try {
        def title = ad.findElement(By.cssSelector(".styles_adTitle__2lU6p")).getText()
        def price = ad.findElement(By.cssSelector(".styles_price__2R3yX")).getText()
        def url = ad.getAttribute("href")
        if (!seenUrls.contains(url)) {
            newListings << [title: title, price: price, url: url]
        } else {
            println "[DEBUG][SEEN] $title | $price | $url"
        }
    } catch (Exception e) {
        println "[ERROR] Exception in ad parsing: ${e.message}"
    }
}
driver.quit()

def csvFile = new File("leboncoin_real.csv")
csvFile.withWriter("UTF-8") { writer ->
    def csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("title", "price", "url"))
    newListings.each { row -> csvPrinter.printRecord([row.title, row.price, row.url]) }
    csvPrinter.flush()
}

if (newListings) {
    // === Build Telegram notification (Markdown) ===
    def header = "🏠 *New Bordeaux Apartments on Leboncoin!*\n\n"
    def bodyTelegram = newListings.collect { l ->
        """🔗 [${l.title}](${l.url})
💶 ${l.price}
"""
    }.join("\n")
    def telegramMessage = header + bodyTelegram

    // === Build Email notification (plain text) ===
    def headerEmail = "New Bordeaux Apartments on Leboncoin:\n\n"
    def bodyEmail = newListings.collect { l ->
        """- ${l.title}
  Price: ${l.price}
  URL: ${l.url}
"""
    }.join("\n")
    def emailMessage = headerEmail + bodyEmail

    // === Send Email ===
    try {
        def props = new Properties()
        props.put("mail.smtp.host", "smtp.gmail.com")
        props.put("mail.smtp.port", "587")
        props.put("mail.smtp.auth", "true")
        props.put("mail.smtp.starttls.enable", "true")
        Session session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EMAIL_USER, EMAIL_PASS)
            }
        })
        Message msg = new MimeMessage(session)
        msg.setFrom(new InternetAddress(EMAIL_USER))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(EMAIL_USER))
        msg.setSubject("New Bordeaux Apartments on Leboncoin")
        msg.setText(emailMessage, "UTF-8")
        Transport.send(msg)
    } catch (Exception e) {
        println "[ERROR] Failed to send email: ${e.message}"
    }

    // === Send Telegram ===
    try {
        def apiUrl = "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage"
        def params = [
            chat_id: TELEGRAM_CHAT_ID,
            text: telegramMessage,
            parse_mode: "Markdown"
        ]
        def queryString = params.collect { k,v -> "$k=" + URLEncoder.encode(v.toString(),"UTF-8") }.join('&')
        new URL(apiUrl + "?" + queryString).text
    } catch (Exception e) {
        println "[ERROR] Failed to send Telegram: ${e.message}"
    }

    // === Save seen URLs ===
    new File(SEEN_FILE) << (newListings.collect{it.url}.join('\n') + '\n')

} else {
    println "No new listings found."
}