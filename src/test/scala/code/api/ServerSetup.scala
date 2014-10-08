/**
Open Bank Project - API
Copyright (C) 2011, 2013, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

 */

package code.api.test

import code.model.{AccountId, BankId}
import org.scalatest._
import dispatch._
import net.liftweb.json.NoTypeHints
import net.liftweb.common._
import org.mortbay.jetty.Server
import org.mortbay.jetty.nio.SelectChannelConnector
import org.mortbay.jetty.webapp.WebAppContext
import net.liftweb.json.Serialization
import net.liftweb.mongodb._
import code.model.dataAccess._
import java.util.Date
import _root_.net.liftweb.util._
import Helpers._
import scala.util.Random._
import scala.math.BigDecimal
import BigDecimal._

trait ServerSetup extends FeatureSpec with SendServerRequests
  with BeforeAndAfterEach with GivenWhenThen
  with BeforeAndAfterAll
  with ShouldMatchers with Loggable{

  var server = ServerSetup
  implicit val formats = Serialization.formats(NoTypeHints)
  val h = Http
  def baseRequest = host(server.host, server.port)

  override def beforeEach() = {
    implicit val dateFormats = net.liftweb.json.DefaultFormats
    //create fake data for the tests

    //fake banks
    val banks = for{i <- 0 until 3} yield {
      createBank(randomString(5))
    }

    //fake bank accounts
    val accounts = banks.flatMap(bank => {
      for { i <- 0 until 2 } yield {
        createAccountAndOwnerView(None, bank, AccountId(randomString(4)), randomString(4))
        }
      })

    accounts.foreach(account => {
      //create public view and another random view (owner view has already been created
      publicView(account.bankId, account.accountId)
      randomView(account.bankId, account.accountId)
    })

    //fake transactions
    accounts.foreach(account => {
      import java.util.Calendar

     val thisAccountBank = OBPBank.createRecord.
        IBAN(randomString(5)).
        national_identifier(account.bankNationalIdentifier).
        name(account.bankName)
      val thisAccount = OBPAccount.createRecord.
        holder(account.holder.get).
        number(account.number.get).
        kind(account.kind.get).
        bank(thisAccountBank)

      def add10Minutes(d: Date): Date = {
        val calendar = Calendar.getInstance
        calendar.setTime(d)
        calendar.add(Calendar.MINUTE, 10)
        calendar.getTime
      }

      val initialDate: Date = {
        val calendar = Calendar.getInstance
        calendar.setTime(new Date())
        calendar.add(Calendar.YEAR, -1)
        calendar.getTime
      }

      object InitialDateFactory{
        val calendar = Calendar.getInstance
        calendar.setTime(initialDate)
        def date: Date = {
          calendar.add(Calendar.HOUR, 10)
          calendar.getTime
        }
      }

      for(i <- 0 until 10){

        val otherAccountBank = OBPBank.createRecord.
          IBAN(randomString(5)).
          national_identifier(randomString(5)).
          name(randomString(5))

        val otherAccount = OBPAccount.createRecord.
          holder(randomString(5)).
          number(randomString(5)).
          kind(randomString(5)).
          bank(otherAccountBank)

        val transactionAmount = BigDecimal(nextDouble * 1000).setScale(2,RoundingMode.HALF_UP)

        val newBalance : OBPBalance = OBPBalance.createRecord.
          currency(account.currency.get).
          amount(account.balance.get + transactionAmount)

        val newValue : OBPValue = OBPValue.createRecord.
          currency(account.currency.get).
          amount(transactionAmount)

        val details ={
          val postedDate = InitialDateFactory.date
          val completedDate = add10Minutes(postedDate)

          OBPDetails
          .createRecord
          .kind(randomString(5))
          .posted(postedDate)
          .other_data(randomString(5))
          .new_balance(newBalance)
          .value(newValue)
          .completed(completedDate)
          .label(randomString(5))
        }
        val transaction = OBPTransaction.createRecord.
          this_account(thisAccount).
          other_account(otherAccount).
          details(details)

        val env = OBPEnvelope.createRecord.
          obp_transaction(transaction).save
        account.balance(newBalance.amount.get).lastUpdate(now).save
        env.createMetadataReference
        env.save
      }
    })
    specificSetup()
  }

  //this method is to run a specific behavior before running each test class
  def specificSetup() = {
  }

  override def afterEach() = {
    //drop the Database after the tests
    MongoDB.getDb(DefaultMongoIdentifier).foreach(_.dropDatabase())
    ViewImpl.findAll.foreach(_.delete_!)
    ViewPrivileges.findAll.foreach(_.delete_!)
    HostedAccount.findAll.foreach(_.delete_!)
    MappedAccountHolder.findAll.foreach(_.delete_!)
  }

  def createAccountAndOwnerView(accountOwner: Option[APIUser], bank: HostedBank, accountId : AccountId, currency : String) = {

    val created = Account.createRecord.
      balance(1000).
      holder(randomString(4)).
      number(randomString(4)).
      kind(randomString(4)).
      name(randomString(4)).
      permalink(accountId.value).
      bankID(bank.id.get).
      label(randomString(4)).
      currency(currency).
      save

    val hostedAccount = HostedAccount.
      create.
      accountID(created.id.get.toString).
      saveMe

    val owner = ownerView(BankId(bank.permalink.get), accountId)

    //give to user1 owner view
    if(accountOwner.isDefined) {
      ViewPrivileges.create.
        view(owner).
        user(accountOwner.get).
        save
    }

    created
  }

  def createPaymentTestBank() =
    createBank("payment-test-bank")

  def createBank(permalink : String) =  HostedBank.createRecord.
    name(randomString(5)).
    alias(randomString(5)).
    permalink(permalink).
    national_identifier(randomString(5)).
    save

  def ownerView(bankId: BankId, accountId: AccountId) =
    ViewImpl.createAndSaveOwnerView(bankId, accountId, randomString(3))

  def publicView(bankId: BankId, accountId: AccountId) =
    ViewImpl.create.
    name_("Public").
    description_(randomString(3)).
    permalink_("public").
    isPublic_(true).
    bankPermalink(bankId.value).
    accountPermalink(accountId.value).
    usePrivateAliasIfOneExists_(false).
    usePublicAliasIfOneExists_(true).
    hideOtherAccountMetadataIfAlias_(true).
    canSeeTransactionThisBankAccount_(true).
    canSeeTransactionOtherBankAccount_(true).
    canSeeTransactionMetadata_(true).
    canSeeTransactionDescription_(true).
    canSeeTransactionAmount_(true).
    canSeeTransactionType_(true).
    canSeeTransactionCurrency_(true).
    canSeeTransactionStartDate_(true).
    canSeeTransactionFinishDate_(true).
    canSeeTransactionBalance_(true).
    canSeeComments_(true).
    canSeeOwnerComment_(true).
    canSeeTags_(true).
    canSeeImages_(true).
    canSeeBankAccountOwners_(true).
    canSeeBankAccountType_(true).
    canSeeBankAccountBalance_(true).
    canSeeBankAccountCurrency_(true).
    canSeeBankAccountLabel_(true).
    canSeeBankAccountNationalIdentifier_(true).
    canSeeBankAccountSwift_bic_(true).
    canSeeBankAccountIban_(true).
    canSeeBankAccountNumber_(true).
    canSeeBankAccountBankName_(true).
    canSeeBankAccountBankPermalink_(true).
    canSeeOtherAccountNationalIdentifier_(true).
    canSeeOtherAccountSWIFT_BIC_(true).
    canSeeOtherAccountIBAN_ (true).
    canSeeOtherAccountBankName_(true).
    canSeeOtherAccountNumber_(true).
    canSeeOtherAccountMetadata_(true).
    canSeeOtherAccountKind_(true).
    canSeeMoreInfo_(true).
    canSeeUrl_(true).
    canSeeImageUrl_(true).
    canSeeOpenCorporatesUrl_(true).
    canSeeCorporateLocation_(true).
    canSeePhysicalLocation_(true).
    canSeePublicAlias_(true).
    canSeePrivateAlias_(true).
    canAddMoreInfo_(true).
    canAddURL_(true).
    canAddImageURL_(true).
    canAddOpenCorporatesUrl_(true).
    canAddCorporateLocation_(true).
    canAddPhysicalLocation_(true).
    canAddPublicAlias_(true).
    canAddPrivateAlias_(true).
    canDeleteCorporateLocation_(true).
    canDeletePhysicalLocation_(true).
    canEditOwnerComment_(true).
    canAddComment_(true).
    canDeleteComment_(true).
    canAddTag_(true).
    canDeleteTag_(true).
    canAddImage_(true).
    canDeleteImage_(true).
    canAddWhereTag_(true).
    canSeeWhereTag_(true).
    canDeleteWhereTag_(true).
    save

  def randomView(bankId: BankId, accountId: AccountId) =
    ViewImpl.create.
    name_(randomString(5)).
    description_(randomString(3)).
    permalink_(randomString(3)).
    isPublic_(false).
    bankPermalink(bankId.value).
    accountPermalink(accountId.value).
    usePrivateAliasIfOneExists_(false).
    usePublicAliasIfOneExists_(false).
    hideOtherAccountMetadataIfAlias_(false).
    canSeeTransactionThisBankAccount_(true).
    canSeeTransactionOtherBankAccount_(true).
    canSeeTransactionMetadata_(true).
    canSeeTransactionDescription_(true).
    canSeeTransactionAmount_(true).
    canSeeTransactionType_(true).
    canSeeTransactionCurrency_(true).
    canSeeTransactionStartDate_(true).
    canSeeTransactionFinishDate_(true).
    canSeeTransactionBalance_(true).
    canSeeComments_(true).
    canSeeOwnerComment_(true).
    canSeeTags_(true).
    canSeeImages_(true).
    canSeeBankAccountOwners_(true).
    canSeeBankAccountType_(true).
    canSeeBankAccountBalance_(true).
    canSeeBankAccountCurrency_(true).
    canSeeBankAccountLabel_(true).
    canSeeBankAccountNationalIdentifier_(true).
    canSeeBankAccountSwift_bic_(true).
    canSeeBankAccountIban_(true).
    canSeeBankAccountNumber_(true).
    canSeeBankAccountBankName_(true).
    canSeeBankAccountBankPermalink_(true).
    canSeeOtherAccountNationalIdentifier_(true).
    canSeeOtherAccountSWIFT_BIC_(true).
    canSeeOtherAccountIBAN_ (true).
    canSeeOtherAccountBankName_(true).
    canSeeOtherAccountNumber_(true).
    canSeeOtherAccountMetadata_(true).
    canSeeOtherAccountKind_(true).
    canSeeMoreInfo_(true).
    canSeeUrl_(true).
    canSeeImageUrl_(true).
    canSeeOpenCorporatesUrl_(true).
    canSeeCorporateLocation_(true).
    canSeePhysicalLocation_(true).
    canSeePublicAlias_(true).
    canSeePrivateAlias_(true).
    canAddMoreInfo_(true).
    canAddURL_(true).
    canAddImageURL_(true).
    canAddOpenCorporatesUrl_(true).
    canAddCorporateLocation_(true).
    canAddPhysicalLocation_(true).
    canAddPublicAlias_(true).
    canAddPrivateAlias_(true).
    canDeleteCorporateLocation_(true).
    canDeletePhysicalLocation_(true).
    canEditOwnerComment_(true).
    canAddComment_(true).
    canDeleteComment_(true).
    canAddTag_(true).
    canDeleteTag_(true).
    canAddImage_(true).
    canDeleteImage_(true).
    canAddWhereTag_(true).
    canSeeWhereTag_(true).
    canDeleteWhereTag_(true).
    save

}

object ServerSetup {
  import net.liftweb.util.Props

  val host = "localhost"
  val port = Props.getInt("tests.port",8000)
  val server = new Server
  val scc = new SelectChannelConnector
  scc.setPort(port)
  server.setConnectors(Array(scc))

  val context = new WebAppContext()
  context.setServer(server)
  context.setContextPath("/")
  context.setWar("src/main/webapp")

  server.addHandler(context)

  server.start()
}