@Grab('org.jsoup:jsoup:1.15.3')
import org.jsoup.Jsoup
import jakarta.mail.*
import jakarta.mail.internet.*
import java.nio.file.*
import java.util.Properties

def EMAIL_USER = System.getenv('EMAIL_USER')
def EMAIL_PASS = System.getenv('EMAIL_PASS')
def TELEGRAM_BOT_TOKEN = System.getenv('TELEGRAM_BOT_TOKEN')
def TELEGRAM_CHAT_ID = System.getenv('TELEGRAM_CHAT_ID')

def LEBONCOIN_URL = "https://www.leboncoin.fr/recherche?category=10&locations=Bordeaux__44.85027430275702_-0.5749636855863279_9036_5000&price=min-730&real_estate_type=2&owner_type=private"

def SEEN_FILE = "leboncoin_seen.txt"

// === SCRAPE LEBONCOIN ===
def doc = Jsoup.connect(LEBONCOIN_URL).userAgent("Mozilla/5.0").get()
def listings = doc.select("a.styles_adCard__2YFTi")

// === LOAD SEEN LISTINGS ===
def seenUrls = new File(SEEN_FILE).exists() ? new File(SEEN_FILE).readLines().toSet() : [] as Set
def newListings = []

listings.each { el ->
    def link = "https://www.leboncoin.fr" + el.attr("href")
    if (!seenUrls.contains(link)) {
        def title = el.select(".styles_adTitle__2lU6p").text() ?: "No title"
        def price = el.select(".styles_price__2R3yX").text() ?: ""
        newListings << [url: link, title: title, price: price]
    }
}

if (newListings) {
    // === BUILD NOTIFICATION: Telegram (Markdown) ===
    def header = "🏠 *New Bordeaux Apartments on Leboncoin!*\n\n"
    def bodyTelegram = newListings.collect { l ->
        """🔗 [${l.title}](${l.url})
💶 ${l.price}
"""
    }.join("\n")
    def telegramMessage = header + bodyTelegram

    // === BUILD NOTIFICATION: Email (plain text) ===
    def headerEmail = "New Bordeaux Apartments on Leboncoin:\n\n"
    def bodyEmail = newListings.collect { l ->
        """- ${l.title}
  Price: ${l.price}
  URL: ${l.url}
"""
    }.join("\n")
    def emailMessage = headerEmail + bodyEmail

    // === SEND EMAIL ===
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

    // === SEND TELEGRAM ===
    def apiUrl = "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage"
    def params = [
        chat_id: TELEGRAM_CHAT_ID,
        text: telegramMessage,
        parse_mode: "Markdown"
    ]
    def queryString = params.collect { k,v -> "$k=" + URLEncoder.encode(v.toString(),"UTF-8") }.join('&')
    new URL(apiUrl + "?" + queryString).text

    // === SAVE SEEN URLS ===
    new File(SEEN_FILE) << (newListings.collect{it.url}.join('\n') + '\n')
} else {
    println "No new listings found."
}