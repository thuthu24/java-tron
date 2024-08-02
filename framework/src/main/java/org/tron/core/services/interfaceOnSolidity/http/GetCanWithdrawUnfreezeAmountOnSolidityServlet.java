package org.tron.core.services.interfaceOnSolidity.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.Wallet;
import org.tron.core.services.annotation.SolidityServlet;
import org.tron.core.services.http.GetCanWithdrawUnfreezeAmountServlet;
import org.tron.core.services.interfaceOnSolidity.WalletOnSolidity;

@SolidityServlet("/walletsolidity/getcanwithdrawunfreezeamount")
@Component
@Slf4j(topic = "API")
public class GetCanWithdrawUnfreezeAmountOnSolidityServlet
        extends GetCanWithdrawUnfreezeAmountServlet {

  @Autowired
  private Wallet wallet;

  @Autowired
  private WalletOnSolidity walletOnSolidity;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    walletOnSolidity.futureGet(() -> super.doGet(request, response));
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    walletOnSolidity.futureGet(() -> super.doPost(request, response));
  }
}
