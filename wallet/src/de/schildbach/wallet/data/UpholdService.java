package de.schildbach.wallet.data;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface UpholdService {

    @FormUrlEncoded
    @POST("oauth2/token")
    Call<AccessToken> getAccessToken(@Field("client_id") String clientId,
                                     @Field("client_secret")String clientSecret,
                                     @Field("code") String code,
                                     @Field("grant_type") String grantType);

    @GET("v0/me/cards")
    Call<List<UpholdCard>> getCards();

    @POST("v0/me/cards")
    Call<UpholdCard> createCard(@Body Map<String, String> body);

}