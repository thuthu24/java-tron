package org.tron.core.services.interfaceOnPBFT.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.services.annotation.PbftServlet;
import org.tron.core.services.http.GetAssetIssueListByNameServlet;
import org.tron.core.services.interfaceOnPBFT.WalletOnPBFT;

@PbftServlet("/getassetissuelistbyname")
@Component
@Slf4j(topic = "API")
public class GetAssetIssueListByNameOnPBFTServlet extends GetAssetIssueListByNameServlet {

  @Autowired
  private WalletOnPBFT walletOnPBFT;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    walletOnPBFT.futureGet(() -> super.doGet(request, response));
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    walletOnPBFT.futureGet(() -> super.doPost(request, response));
  }
}
