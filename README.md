# ayatori

**STATUS**: Pre-alpha, in design and prototyping phase.

ayatori is an experimental LRA (Long Running Action) Coordinator (transaction manager) and client libraries that try to follow (not completely) [Eclipse Microprofile LRA Spec](https://download.eclipse.org/microprofile/microprofile-lra-1.0/microprofile-lra-spec-1.0.html). 

Of course, It is not ready for production usage, yet :)

## Latest version

not released


## Quick start

ayatori is an LRA Coordinator (a.k.a saga coordinator) to manage distributed transactions across microservices. You can find further information (the model, sequence diagrams etc.)  at [Eclipse Microprofile LRA Spec](https://download.eclipse.org/microprofile/microprofile-lra-1.0/microprofile-lra-spec-1.0.html). 

### Run the LRA Coordinator

Clone the repo and cd into it. Project uses [Polylith](https://polylith.gitbook.io/) architecture. So, you can use Polylith commands. 

Let's get project info

``` 
$ clojure -M:poly info
```

You can run the application from the root workspace:

``` 
$ clojure -M:dev -m ayatori.rest-api.main
INFO: web server running at 0.0.0.0 3000
```

you can check the API by Swagger UI at [http://localhost:3000/api-docs](http://localhost:3000/api-docs)
    
### Usage example 

The LRA Coordinator is ready. Let's create a few web services that use it to manage their distributed transactions. The example microservices are very simple. Each service takes an integer, adds 1 to it and pass it to the next webservice. You can find the complete source code in [examples](https://github.com/serefayar/ayatori/tree/main/examples) directory.

We gonna use reitit router for the example microservices. (ayatori supports only reitit ring router, for now)

Let's define LRA contexts for each microservice. for that, we gonna use reitit's route data feature by adding new keyword :lra to define the LRA context

Service 1 

``` clojure

    ;; code omitted...
    
    ["/service1"
     ["/order"
      {:lra {:id :order ;; id of the context
             :type :requires-new} ;; generate new LRA context and use it 
                                  ;; even if already called inside an LRA context
       :post {:parameters {:query {:num int?}}
              :handler a-handler}}]
     ["/compansate"
      {:lra {:id :order ;; id of the context 
             :type :compansate} ;; compansating action for :order context. 
       :put {:handler compansate-handler}}]
     ["/complete"
      {:lra {:id :order ;; id of the context
             :type :complete} ;; complete action for :order context
       :put {:handler complete-handler}}]]
    
    ;; rest of the code omitted...

```

Service 2

``` clojure

    ;; code omitted...
    ["/service2"
     ["/order"
      {:lra {:id :order-s2 ;; id of the context
             :type :mandatory} ;; it must be called in an LRA context otherwise the it is not executed 
                               ;; and 412 http status code returned to the caller
       :put {:parameters {:query {:num int?}}
             :handler a-handler}}]
     ["/compansate"
      {:lra {:id :order-s2 ;; id of the context
             :type :compansate} ;; compansating action for :order-s2 context. 
       :put {:handler compansate-handler}}]
     ["/complete"
      {:lra {:id :order-s2 ;; id of the context
             :type :complete} ;; complete action for :order-s2 context
       :put {:handler complete-handler}}]]
       
    ;; rest of the code omitted...

```

Service 3

``` clojure

    ;; code omitted...
    ["/service3"
     ["/order"
      {:lra {:id :order-s3 ;; id of the context
             :type :mandatory} ;; it must be called in an LRA context otherwise the it is not executed 
                               ;; and 412 http status code returned to the caller
       :put {:parameters {:query {:num int?}}
             :handler a-handler}}]
     ["/compansate"
      {:lra {:id :order-s3
             :type :compansate} ;; compansating action for :order-s3 context. 
       :put {:handler compansate-handler}}]
     ["/complete"
      {:lra {:id :order-s3
             :type :complete} ;; complete action for :order-s3 context
       :put {:handler complete-handler}}]]

    ;; rest of the code omitted...
```


and let's add ayatori middleware to each one and run the microservices

``` clojure
    
    ;; code omitted...
    {:data {;; .... other middlewares
        [ayatori/create-lra-middleware {:coordinator-url "http://localhost:3000/lra-coordinator"}]}}

    ;; rest of the code omitted...
    
```

then call the first microservice to test the LRA context.

``` 
$ http post "http://localhost:4000/service1/order?num=1"

HTTP/1.1 200 OK
Content-Length: 1
Date: Tue, 01 Feb 2022 07:00:47 GMT
Server: Jetty(9.4.12.v20180830)

4

```

let's look at the logs 

### Complete

service 1:

``` 
"service1 param 1, lra context created with code cc6873e9-1193-4d88-b1d6-c2acc81c6c86" (1)
INFO: closing lra cc6873e9-1193-4d88-b1d6-c2acc81c6c86
"service1 completing lra cc6873e9-1193-4d88-b1d6-c2acc81c6c86"                         (4)
```

service 2:
``` 
"service2 param 2, joined to lra context cc6873e9-1193-4d88-b1d6-c2acc81c6c86"         (2)
"service2 completing lra cc6873e9-1193-4d88-b1d6-c2acc81c6c86"                         (5)

```
service 3:

``` 
"service3 param 3, joined to lra context cc6873e9-1193-4d88-b1d6-c2acc81c6c86"         (3)
"service3 completing lra cc6873e9-1193-4d88-b1d6-c2acc81c6c86"                         (6)

```

what's happened in order:

1. LRA context created when received the request, before executing the handler
2. Joined to LRA Context when received the request from service1
3. Joined to LRA Context when received the request from service2
Service1 completed successfully 
4. complete request received from the LRA Coordinator
5. complete request received from the LRA Coordinator
6. complete request received from the LRA Coordinator


### Compansate

let's say the last service has failed and the error code is returned. the result will look like this

service 1:

``` 
"service1 param 1, lra context created with code a327a70b-65ae-4173-97a3-94f7f87994cf"  (1)
INFO: cancelling lra a327a70b-65ae-4173-97a3-94f7f87994cf
"service1 compansating lra a327a70b-65ae-4173-97a3-94f7f87994cf"                        (6)


```

service 2:
``` 
"service2 param 2, joined to lra context a327a70b-65ae-4173-97a3-94f7f87994cf"          (2)
"service2 compansating lra a327a70b-65ae-4173-97a3-94f7f87994cf"                        (5)

```
service 3:

``` 
"service3 param 3, joined to lra context a327a70b-65ae-4173-97a3-94f7f87994cf"          (3) 
"service3 compansating lra a327a70b-65ae-4173-97a3-94f7f87994cf"                        (4)


```

what's happened in order:

1. LRA context created when received the request, before executing the handler
2. Joined to LRA Context when received the request from service1
3. Joined to LRA Context when received the request from service2. 
   service3 return http status 400
   call cancel on the LRA coordinator. 
   LRA Coordinator call compansate handler in reverse order
4. conpansate request received from the LRA coordinator
5. conpansate request received from the LRA coordinator
6. conpansate request received from the LRA coordinator
    

## Known alternatives

- [narayana](https://narayana.io/)
- ...


## TODO

- [ ] more Microprofile LRA Spec features
- [ ] stabilize the api
- [ ] better documentation
- [ ] no tests, really? write some test, will you?



## License

Copyright © 2022 Şeref R. Ayar

Distributed under the MIT License.
