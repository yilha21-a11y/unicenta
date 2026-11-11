//    uniCenta oPOS  - Touch Friendly Point Of Sale
//    Copyright (c) 2009-2018 uniCenta & previous Openbravo POS works
//    https://unicenta.com
//
//    This file is part of uniCenta oPOS
//
//    uniCenta oPOS is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//   uniCenta oPOS is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with uniCenta oPOS.  If not, see <http://www.gnu.org/licenses/>.

//    OLPOS : terminal PaymentSense retire (il passait par le module binaire
//    unicenta-plugins, sans source ni licence, non inclus dans OLPOS).

package com.unicenta.pos.payment;

public class PaymentGatewayPaymentSense implements PaymentGateway {

    public PaymentGatewayPaymentSense() {
    }

    @Override
    public void execute(PaymentInfoMagcard payinfo) {
        payinfo.paymentError("PaymentSense n'est pas disponible dans OLPOS : utilisez un autre moyen de paiement.",
                "Non disponible");
    }
}
